package socket

import (
	"context"
	"encoding/json"
	"errors"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/redis/go-redis/v9"
	"time"
)

type Owner struct {
	Pod        string `json:"pod"`
	Session    string `json:"session"`
	Generation int64  `json:"generation"`
}
type Ownership struct {
	DB    *pgxpool.Pool
	Redis *redis.Client
	Pod   string
}

func (o Ownership) Claim(ctx context.Context, id Identity) (Owner, error) {
	var gen int64
	err := o.DB.QueryRow(ctx, "SELECT nextval('connection_generation')").Scan(&gen)
	if err != nil {
		return Owner{}, err
	}
	v := Owner{o.Pod, id.Session, gen}
	data, _ := json.Marshal(v)
	// Compare in Redis so a delayed earlier claim cannot replace a newer socket.
	result, err := o.Redis.Eval(ctx, `local old=redis.call('GET',KEYS[1]); if old and cjson.decode(old).generation >= tonumber(ARGV[2]) then return 0 end; redis.call('SET',KEYS[1],ARGV[1],'PX',6000); return 1`, []string{"rt:owner:" + id.User}, string(data), gen).Int()
	if err == nil && result != 1 {
		err = errors.New("newer connection exists")
	}
	return v, err
}
func (o Ownership) Renew(ctx context.Context, user string, owner Owner) error {
	data, _ := json.Marshal(owner)
	result, err := o.Redis.Eval(ctx, `if redis.call('GET',KEYS[1])~=ARGV[1] then return 0 end; redis.call('PEXPIRE',KEYS[1],6000);return 1`, []string{"rt:owner:" + user}, string(data)).Int()
	if err == nil && result != 1 {
		err = errors.New("ownership lost")
	}
	return err
}
func (o Ownership) Lookup(ctx context.Context, user string) (Owner, error) {
	data, err := o.Redis.Get(ctx, "rt:owner:"+user).Bytes()
	var v Owner
	if err == nil {
		err = json.Unmarshal(data, &v)
	}
	return v, err
}
func (o Ownership) Release(ctx context.Context, user string, owner Owner) error {
	data, _ := json.Marshal(owner)
	return o.Redis.Eval(ctx, `if redis.call('GET',KEYS[1])==ARGV[1] then return redis.call('DEL',KEYS[1]) end;return 0`, []string{"rt:owner:" + user}, string(data)).Err()
}
func (o Ownership) Ready(ctx context.Context) error {
	ctx, cancel := context.WithTimeout(ctx, time.Second)
	defer cancel()
	return o.Redis.Ping(ctx).Err()
}

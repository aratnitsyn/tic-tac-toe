package command

import (
	"bytes"
	"testing"
)

func TestNormalizePropertyOrder(t *testing.T) {
	left, err := normalize([]byte(`{"cell":1,"version":0}`))
	if err != nil {
		t.Fatal(err)
	}
	right, err := normalize([]byte(`{ "version": 0, "cell": 1 }`))
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(left, right) {
		t.Fatal("object property order changed command identity")
	}
}

func TestNormalizeRejectsTrailingDocument(t *testing.T) {
	if _, err := normalize([]byte(`{} {}`)); err == nil {
		t.Fatal("expected invalid payload error")
	}
}

import { useRef } from 'react';
import type { Game } from '../api/contract';

export function Board({
    game,
    userId,
    disabled,
    move,
}: {
    game: Game;
    userId: string;
    disabled: boolean;
    move: (cell: number) => void;
}) {
    const cellRef = useRef<Array<HTMLButtonElement | null>>([]);
    const mine = game.xPlayerId === userId ? 'X' : 'O';
    const active = game.state === mine + '_TURN' && !disabled;
    function navigate(index: number, key: string) {
        const delta: Record<string, number> = {
            ArrowRight: 1,
            ArrowLeft: -1,
            ArrowDown: 3,
            ArrowUp: -3,
            Home: -index,
            End: 8 - index,
        };
        const step = delta[key];
        if (step === undefined) return false;
        cellRef.current[(index + step + 9) % 9]?.focus();
        return true;
    }
    return (
        <div
            className="board"
            role="group"
            aria-label="Игровое поле 3 на 3. Стрелки для перемещения, Enter или пробел для хода."
        >
            {game.board.map((cell, i) => (
                <button
                    key={i}
                    ref={(node) => {
                        cellRef.current[i] = node;
                    }}
                    className={'cell ' + cell.toLowerCase()}
                    aria-label={`Ряд ${Math.floor(i / 3) + 1}, столбец ${(i % 3) + 1}: ${cell === 'EMPTY' ? 'пусто' : cell}`}
                    aria-disabled={!active || cell !== 'EMPTY'}
                    onClick={() => {
                        if (active && cell === 'EMPTY') move(i);
                    }}
                    onKeyDown={(event) => {
                        if (navigate(i, event.key)) event.preventDefault();
                    }}
                >
                    {cell === 'EMPTY' ? (
                        <span className="cell-dot" aria-hidden="true">
                            ·
                        </span>
                    ) : (
                        cell
                    )}
                </button>
            ))}
        </div>
    );
}

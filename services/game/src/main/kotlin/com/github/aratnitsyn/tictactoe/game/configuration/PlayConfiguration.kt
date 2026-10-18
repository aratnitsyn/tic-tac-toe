package com.github.aratnitsyn.tictactoe.game.configuration

import com.github.aratnitsyn.tictactoe.game.participation.CoordinationDatabase
import com.github.aratnitsyn.tictactoe.game.play.PlayDeadlineStore
import com.github.aratnitsyn.tictactoe.game.play.PlayEngine
import com.github.aratnitsyn.tictactoe.game.play.PlayService
import com.github.aratnitsyn.tictactoe.game.play.PlayStore
import com.github.aratnitsyn.tictactoe.game.preparation.PreparationEvent
import com.github.aratnitsyn.tictactoe.game.preparation.PreparationService
import com.github.aratnitsyn.tictactoe.game.preparation.PreparationStore
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class PlayConfiguration {
    @Bean fun playEngine(
        db: CoordinationDatabase,
        event: PreparationEvent,
    ) = PlayEngine(db, PlayStore(db), event, PlayDeadlineStore(db))

    @Bean fun playService(
        preparation: PreparationService,
        matches: PreparationStore,
        engine: PlayEngine,
    ) = PlayService(preparation, matches, engine)
}

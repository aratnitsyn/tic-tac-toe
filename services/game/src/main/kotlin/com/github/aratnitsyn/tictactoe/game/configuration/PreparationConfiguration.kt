package com.github.aratnitsyn.tictactoe.game.configuration

import com.github.aratnitsyn.tictactoe.game.participation.CoordinationDatabase
import com.github.aratnitsyn.tictactoe.game.persistence.CommandStore
import com.github.aratnitsyn.tictactoe.game.play.PlayEngine
import com.github.aratnitsyn.tictactoe.game.preparation.PreparationEvent
import com.github.aratnitsyn.tictactoe.game.preparation.PreparationReturn
import com.github.aratnitsyn.tictactoe.game.preparation.PreparationService
import com.github.aratnitsyn.tictactoe.game.preparation.PreparationStore
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class PreparationConfiguration {
    @Bean fun preparationStore(db: CoordinationDatabase) = PreparationStore(db)

    @Bean fun preparationEvent(db: CoordinationDatabase) = PreparationEvent(db)

    @Bean fun preparationReturn(
        db: CoordinationDatabase,
        event: PreparationEvent,
    ) = PreparationReturn(db, event)

    @Bean fun preparationService(
        store: PreparationStore,
        event: PreparationEvent,
        returns: PreparationReturn,
        play: PlayEngine,
    ) = PreparationService(store, CommandStore(store.db.source, store.db.mapper), event, returns, play = play)
}

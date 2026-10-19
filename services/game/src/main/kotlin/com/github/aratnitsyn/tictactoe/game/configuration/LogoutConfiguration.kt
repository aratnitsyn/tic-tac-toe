package com.github.aratnitsyn.tictactoe.game.configuration

import com.github.aratnitsyn.tictactoe.game.logout.LogoutService
import com.github.aratnitsyn.tictactoe.game.play.PlayEngine
import com.github.aratnitsyn.tictactoe.game.preparation.PreparationEvent
import com.github.aratnitsyn.tictactoe.game.preparation.PreparationReturn
import com.github.aratnitsyn.tictactoe.game.preparation.PreparationService
import com.github.aratnitsyn.tictactoe.game.preparation.PreparationStore
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class LogoutConfiguration {
    @Bean
    fun logoutService(
        store: PreparationStore,
        preparation: PreparationService,
        play: PlayEngine,
        returns: PreparationReturn,
        events: PreparationEvent,
    ) = LogoutService(store, preparation, play, returns, events)
}

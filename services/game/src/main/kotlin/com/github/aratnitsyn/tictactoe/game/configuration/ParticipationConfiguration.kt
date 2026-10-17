package com.github.aratnitsyn.tictactoe.game.configuration
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.aratnitsyn.tictactoe.game.participation.CoordinationDatabase
import com.github.aratnitsyn.tictactoe.game.participation.OperationStore
import com.github.aratnitsyn.tictactoe.game.participation.PairAssignment
import com.github.aratnitsyn.tictactoe.game.participation.ParticipationEvent
import com.github.aratnitsyn.tictactoe.game.participation.ParticipationService
import com.github.aratnitsyn.tictactoe.game.participation.ParticipationTransition
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import javax.sql.DataSource

@Configuration
@EnableScheduling
class ParticipationConfiguration {
    @Bean fun database(
        source: DataSource,
        mapper: ObjectMapper,
    ) = CoordinationDatabase(source, mapper)

    @Bean fun operations(db: CoordinationDatabase) = OperationStore(db)

    @Bean fun events(db: CoordinationDatabase) = ParticipationEvent(db)

    @Bean fun transitions(
        db: CoordinationDatabase,
        events: ParticipationEvent,
    ) = ParticipationTransition(db, events)

    @Bean fun assignments(
        db: CoordinationDatabase,
        transitions: ParticipationTransition,
        events: ParticipationEvent,
    ) = PairAssignment(db, transitions, events)

    @Bean fun participation(
        db: CoordinationDatabase,
        operations: OperationStore,
        transitions: ParticipationTransition,
        assignments: PairAssignment,
    ) = ParticipationService(db, operations, transitions, assignments)
}

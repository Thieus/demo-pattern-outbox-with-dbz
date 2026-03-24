package com.mla.demo.service.team;

import com.mla.demo.avro.MemberEvent;
import com.mla.demo.avro.TeamEvent;
import com.mla.demo.service.team.entities.MemberEntity;
import com.mla.demo.service.team.entities.TeamEntity;
import com.mla.demo.service.outbox.OutboxEventPublisher;
import com.mla.demo.service.outbox.OutboxEventType;
import com.mla.demo.service.outbox.OutboxProducer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class TeamService {

    private final TeamRepository teamRepository;
    private final OutboxProducer<UUID, TeamEvent> teamEventProducer;

    public TeamService(TeamRepository teamRepository, OutboxEventPublisher outboxEventPublisher) {
        this.teamRepository = teamRepository;
        this.teamEventProducer = new OutboxProducer<>(outboxEventPublisher, "TeamEvent");
    }

    @Transactional
    public TeamEntity createTeamWithMembers(String teamName, String description, List<String> memberNames) {
        final TeamEntity team = new TeamEntity();
        team.setName(teamName);
        team.setDescription(description);

        memberNames.forEach(name -> {
            MemberEntity member = new MemberEntity();
            member.setName(name);
            member.setEmail(name.toLowerCase().replace(" ", ".") + "@example.com");
            team.addMember(member);
        });

        TeamEntity teamSaved = teamRepository.save(team);

        TeamEvent teamEvent = TeamEvent.newBuilder()
                .setId(teamSaved.getId().toString())
                .setName(teamSaved.getName())
                .setDescription(teamSaved.getDescription())
                .setMembers(teamSaved.getMembers().stream().map(m -> MemberEvent.newBuilder()
                                .setId(m.getId().toString())
                                .setName(m.getName())
                                .build())
                        .toList())
                .build();

        teamEventProducer.publish(teamSaved.getId(), teamEvent, OutboxEventType.CREATE);

        return team;
    }
}

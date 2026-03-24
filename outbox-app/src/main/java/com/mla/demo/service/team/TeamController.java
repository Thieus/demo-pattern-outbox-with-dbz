package com.mla.demo.service.team;

import com.mla.demo.service.team.entities.TeamEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/teams")
@Tag(name = "Teams", description = "API de gestion des équipes")
public class TeamController {

    private final TeamService teamService;

    public TeamController(TeamService teamService) {
        this.teamService = teamService;
    }

    @PostMapping
    @Operation(summary = "Créer une équipe", description = "Crée une équipe, ses membres, puis publie un événement outbox")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Équipe créée avec succès", content = @Content(schema = @Schema(implementation = TeamEntity.class))),
            @ApiResponse(responseCode = "400", description = "Requête invalide")
    })
    public ResponseEntity<TeamEntity> createTeam(@Valid @RequestBody CreateTeamRequest request) {
        TeamEntity teamEntity = teamService.createTeamWithMembers(request.name(), request.description(), request.members());

        return ResponseEntity.ok(teamEntity);
    }
}

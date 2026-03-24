package com.mla.demo.service.team;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

@Schema(name = "CreateTeamRequest", description = "Requête de création d'une équipe avec ses membres")
public record CreateTeamRequest(
        @Schema(description = "Nom de l'équipe", example = "Platform Engineering")
        @NotBlank(message = "name is required")
        String name,

        @Schema(description = "Description de l'équipe", example = "Équipe responsable de la plateforme technique")
        String description,

        @Schema(description = "Liste des membres de l'équipe", example = "[\"Alice Martin\", \"Bob Dupont\"]")
        @NotEmpty(message = "members must contain at least one member")
        List<@NotBlank(message = "member name must not be blank") String> members
) {
}


CREATE TABLE team (
      id UUID PRIMARY KEY,
      name VARCHAR(255) NOT NULL,
      description TEXT
);

CREATE TABLE member (
        id UUID PRIMARY KEY,
        name VARCHAR(255) NOT NULL,
        email VARCHAR(255) UNIQUE
);

CREATE TABLE team_member (
     team_id UUID NOT NULL,
     member_id UUID NOT NULL,
     PRIMARY KEY (team_id, member_id),
     CONSTRAINT fk_team FOREIGN KEY (team_id) REFERENCES team(id),
     CONSTRAINT fk_member FOREIGN KEY (member_id) REFERENCES member(id)
);

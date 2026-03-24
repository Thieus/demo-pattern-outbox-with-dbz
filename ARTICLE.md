# Outbox Pattern + Debezium + Strict Avro: Stop Schema Drift at the Source

## Introduction

Dans une architecture microservices orientée événements, un service doit fréquemment effectuer deux opérations
distinctes : **persister** une donnée métier en base et **publier** un événement dans Kafka. Cette double écriture
introduit un problème bien connu : le *Dual-Write Problem*.

Dès que ces deux opérations ne sont plus **atomiques**, le système devient fragile. Si la base de données valide la
transaction mais que Kafka est indisponible, l’événement est perdu. À l’inverse, si l’événement est publié mais que la
transaction échoue, on expose des données incohérentes aux consommateurs. Dans les deux cas, la confiance dans le
système est compromise.

Le **pattern Outbox** apporte une réponse élégante à ce problème. Plutôt que de publier directement dans Kafka,
l’événement est écrit dans une table dédiée, au sein de la même transaction que la donnée métier. Cette approche
garantit que l’état métier et l’événement associé évoluent de manière strictement cohérente.

Pour extraire ces événements de la base de données sans impacter les performances de l'application, on s'appuie sur le
mécanisme interne du moteur de base de données : le **WAL (Write-Ahead Log)**. Ce journal de transactions enregistre 
séquentiellement chaque modification avant même qu'elle ne soit appliquée aux données persistantes. 

La technique consistant à écouter ce journal pour capturer les modifications s'appelle le **CDC (Change Data Capture)**.
Contrairement à une interrogation périodique chronophage (polling), le CDC permet de réagir au fil de l'eau aux nouveaux
enregistrements du WAL.

C'est pour implémenter cette approche que **Debezium** s'est imposé comme la solution de référence. Connecté directement
à PostgreSQL, il lit le WAL en continu via la réplication logique et se charge de propager de manière asynchrone et 
fiable les événements de la table Outbox vers Kafka.

![Pattern Outbox](./img/patternOutbox.png)

À ce stade, le problème d’atomicité est résolu. Mais un autre risque, souvent sous-estimé, apparaît : la dérive de
schéma.

---

## Les limites de l’Outbox classique

Dans la majorité des implémentations, le payload de la table Outbox est stocké en **JSON**. Cette approche a
l’avantage d’être simple, mais elle introduit une faiblesse structurelle : l’absence de contrat strict.

Rien n’empêche réellement un développeur de modifier la structure du payload sans coordination. Le système tolère ces
écarts jusqu’au moment où un consumer casse en production. Le problème est alors détecté trop tard, et surtout au
mauvais endroit.

Ce modèle repose implicitement sur une discipline humaine. Or, dans un système distribué, la robustesse ne doit jamais
dépendre de conventions implicites. Elle doit être imposée par le système lui-même.

---

## Imposer un contrat avec l’approche “Strict Avro”

L’idée est simple : déplacer la validation du schéma au plus tôt, c’est-à-dire côté **producteur**, avant même
l’écriture en base.

Concrètement, le payload n’est plus stocké en **JSON**, mais sous forme **binaire**, après sérialisation via
`KafkaAvroSerializer`. Cela signifie que chaque événement inséré dans la table Outbox est déjà conforme à un schéma
validé par le **Schema Registry**. Si ce n’est pas le cas, la sérialisation échoue immédiatement et la transaction
est **rollbackée**.

Ce changement a un impact majeur. On ne se contente plus de transporter des données, on garantit leur validité
structurelle dès leur création. La base de données ne peut plus contenir d’événements invalides, et Kafka ne devient
plus un point de découverte d’erreurs.

![OutboxEventPublisher.png](./img/OutboxEventPublisher.png)

**Debezium**, dans ce modèle, perd volontairement toute responsabilité de transformation. Il ne fait que **transporter**
un payload binaire déjà validé. Cette simplification est un avantage : moins de logique intermédiaire, moins de points
de défaillance.

## Tableau comparatif : JSON Outbox vs Strict Avro Outbox

| Critère | JSON Outbox | Strict Avro Outbox |
|---|---|---|
| Format du payload stocké | JSON lisible en base | Binaire Avro (`BYTEA`) |
| Contrat de schéma | Faible, souvent implicite | Fort, imposé par le schéma Avro |
| Moment de détection des erreurs | Souvent côté consumer ou en production | Dès la sérialisation côté producteur |
| Risque de schema drift | Élevé si la discipline n’est pas stricte | Fortement réduit par la validation en amont |
| Rôle de Debezium / Kafka Connect | Peut nécessiter davantage d’interprétation ou de transformation | Transporte un payload déjà validé |
| Lisibilité en base | Excellente pour le debug manuel | Faible sans outil de décodage |
| Couplage à l’écosystème Kafka | Plus faible | Plus fort (Schema Registry, Avro, sérialiseur) |
| Gouvernance des évolutions | Repose davantage sur les équipes et les conventions | Encadrée par les règles de compatibilité du Schema Registry |
| Complexité de mise en œuvre | Plus simple au démarrage | Plus exigeante, mais plus robuste à grande échelle |

En pratique, **JSON Outbox** maximise la simplicité et la lisibilité, tandis que **Strict Avro Outbox** privilégie la
**fiabilité contractuelle**, la **détection précoce des erreurs** et la **gouvernance des schémas**.

## Une chaîne de responsabilité claire

Avec cette approche, chaque composant a un rôle strictement défini.

L’application est responsable de la **conformité** des événements. Elle sérialise les payloads et garantit leur
compatibilité avec le schéma. La base de données assure **l’atomicité** entre la donnée métier et l’événement.
**Debezium**, de son côté, se contente de lire le WAL et de publier dans Kafka sans interpréter le contenu.

Cette séparation nette des responsabilités rend le système beaucoup plus prévisible. Il devient **impossible**
d’introduire un événement invalide sans que cela échoue immédiatement, au plus près de la source.

---

# Mise en place

## Configuration PostgreSQL

Pour permettre à Debezium de fonctionner correctement, PostgreSQL doit être configuré en réplication logique.
Cela passe notamment par l’activation du paramètre `wal_level` en mode `logical`. Sans cela, aucun mécanisme de CDC
n’est possible. Les paramètres liés aux replication slots et aux WAL senders doivent également être dimensionnés
correctement.

### Paramètres requis

```
wal_level=logical
max_replication_slots=5
max_wal_senders=5
-- Check value
SHOW wal_level;
SHOW max_replication_slots;
SHOW max_wal_senders;
```

### Création de la table Outbox et HeartBeat

La table Outbox est au cœur du pattern. Elle doit être créée et utilisée par le même utilisateur que celui de
l’application métier, et non par Debezium. Ce point est important : l’écriture dans l’Outbox fait partie de la
transaction métier, elle doit donc rester sous le contrôle de l’application.

La table `debezium_heartbeat` est quant à elle requise pour maintenir le connecteur actif et gérer l'avancée du WAL 
(nous reviendrons sur ce mécanisme plus bas).

```sql
CREATE TABLE IF NOT EXISTS outbox_events (
    id                  UUID PRIMARY KEY,
    topic               VARCHAR(255) NOT NULL,
    aggregate_id        VARCHAR(255) NOT NULL,
    event_type          VARCHAR(50) NOT NULL,
    payload             BYTEA NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
                                      );

CREATE TABLE IF NOT EXISTS debezium_heartbeat (
  id INT PRIMARY KEY,
  last_heartbeat TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO debezium_heartbeat (id, last_heartbeat) VALUES (1, CURRENT_TIMESTAMP) ON CONFLICT (id) DO NOTHING;
```

### Configuration des permissions

L’utilisation d’un utilisateur dédié (`debezium_user`) pour le CDC est fortement recommandée. Cela permet de cloisonner
les responsabilités et d’appliquer le principe du moindre privilège. Cet utilisateur n’a pas vocation à écrire dans
la base. Il se contente de lire les changements via le mécanisme de réplication logique. Le rôle `REPLICATION` est
indispensable, car il permet à Debezium de créer et d’utiliser un **replication slot**, qui est le mécanisme
garantissant qu’aucun événement du WAL ne sera perdu.

```sql
CREATE ROLE debezium_user WITH REPLICATION LOGIN PASSWORD 'password';

GRANT CONNECT ON DATABASE defaultdb TO debezium_user;
GRANT USAGE ON SCHEMA public TO debezium_user;

GRANT SELECT ON TABLE public.outbox_events TO debezium_user;
GRANT SELECT, UPDATE ON TABLE public.debezium_heartbeat TO debezium_user;
```

### Publication logique

La création explicite de la publication est un choix volontaire. Debezium est capable de la créer automatiquement,
mais garder le contrôle côté base permet de maîtriser précisément quelles tables sont exposées. Dans ce cas, seules
les tables `outbox_events` et `debezium_heartbeat` sont incluses. Cela évite d’exposer inutilement d’autres tables
et réduit la charge de traitement.

```sql
CREATE PUBLICATION dbz_publication FOR TABLE 
       public.outbox_events, 
       public.debezium_heartbeat 
       WITH (publish='insert,update');
```

### Vérifications

```sql
SELECT * FROM pg_publication;
SELECT * FROM pg_publication_tables;
SELECT * FROM pg_replication_slots;
```

### Contrôle du replication slot

Le replication slot est un élément critique du dispositif. Il garantit que PostgreSQL conserve les segments du WAL
tant que Debezium ne les a pas consommés. C’est ce qui permet une lecture fiable et sans perte. Cependant, ce mécanisme
a un effet de bord important : si le connecteur est arrêté ou en retard, les WAL ne sont plus purgés. L’espace disque
peut alors croître rapidement.

Pour surveiller cet état, la requête suivante est essentielle :

```sql
SELECT slot_name,
       active,
       restart_lsn,
       pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)) AS retained_wal
FROM pg_replication_slots;
```

Cette requête permet de voir :

- si le slot est actif
- combien de WAL sont retenus

En production, cette métrique doit être monitorée. Un slot inactif est un signal d’alerte immédiat.

## Connector configuration

Le connecteur Debezium PostgreSQL est déployé sur Kafka Connect. Voici une configuration prête pour la production :

```json
{
  "name": "postgres-to-kafka-connector",
  "config": {
    "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
    "database.hostname": "your_hostname",
    "database.port": "5432",
    "database.user": "debezium_user",
    "database.password": "${file:/opt/vault_secrets/connector-dbz.properties:db_password}",
    "database.dbname": "defaultdb",
    "tasks.max": "1",
    "table.include.list": "public.outbox_events",
    "topic.prefix": "demo",
    "plugin.name": "pgoutput",
    "slot.name": "debezium_slot",
    "snapshot.mode": "initial",
    "publication.name": "dbz_publication",
    "publication.autocreate.mode": "filtered",
    "key.converter": "org.apache.kafka.connect.storage.StringConverter",
    "key.converter.schemas.enable": "false",
    "value.converter": "io.debezium.converters.BinaryDataConverter",
    "value.converter.delegate.converter.type": "io.confluent.connect.avro.AvroConverter",
    "value.converter.delegate.converter.type.basic.auth.credentials.source": "USER_INFO",
    "value.converter.delegate.converter.type.basic.auth.user.info": "${file:/opt/vault_secrets/connector-dbz.properties:sr_user}:${file:/opt/vault_secrets/connector-dbz.properties:sr_pwd}",
    "value.converter.delegate.converter.type.schema.registry.url": "your_url_schema_registry",
    "transforms": "outbox",
    "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
    "transforms.outbox.route.by.field": "topic",
    "transforms.outbox.route.topic.replacement": "${routedByValue}",
    "transforms.outbox.table.field.event.id": "id",
    "transforms.outbox.table.field.event.key": "aggregate_id",
    "transforms.outbox.table.field.event.payload": "payload",
    "transforms.outbox.table.field.event.type": "event_type",
    "transforms.outbox.table.fields.additional.placement": "event_type:header:eventType,created_at:header:messageTimestamp",
    "topic.creation.default.replication.factor": 1,
    "topic.creation.default.partitions": 1,
    "topic.heartbeat.prefix": "demo-heartbeat",
    "heartbeat.interval.ms": 10000,
    "heartbeat.action.query": "UPDATE public.debezium_heartbeat SET last_heartbeat = NOW() WHERE id = 1",
    "errors.tolerance": "none",
    "errors.log.enable": "true",
    "errors.log.include.messages": "true",
    "tombstones.on.delete": "false"
  }
}
```

L'utilisation de `"value.converter": "io.debezium.converters.BinaryDataConverter"` est ici primordiale : ce 
convertisseur spécifique indique à Kafka Connect de prendre le champ `BYTEA` depuis la base de données et de 
l'injecter *tel quel* dans le message Kafka, sans chercher à le désérialiser ou à l'interpréter, préservant ainsi le 
binaire Avro généré nativement par l'application.

De plus, les propriétés `transforms.outbox.*` configurent l'`EventRouter` SMT (Single Message Transform) de Debezium 
pour extraire le payload binaire de l'Outbox et le router dynamiquement vers le bon topic Kafka.

### Heartbeat et gestion du WAL

Le mécanisme de heartbeat permet de forcer l’écriture régulière dans le WAL, via une requête exécutée toutes les 10
secondes :

```sql
UPDATE public.debezium_heartbeat
SET last_heartbeat = NOW()
WHERE id = 1;
```

Le heartbeat sert à :

- **prouver que le connecteur avance** : même en l'absence d'événements métier, on distingue un connecteur sain d'un connecteur bloqué.
- **éviter l’accumulation de WAL** : en générant du trafic régulier, le replication slot avance, ce qui permet le nettoyage des anciens segments.

⚠️ Sans heartbeat, un système peu actif peut accumuler des WAL inutilement et saturer l'espace disque.

## Déploiement du connecteur

Le connecteur Debezium est déployé via l’API REST de Kafka Connect. L’état retourné par l’endpoint `/status` permet de
vérifier rapidement si le connecteur est en erreur, en cours d’exécution ou en redémarrage. Le slot de réplication étant
persistant côté PostgreSQL, un redéploiement du connecteur n’entraîne pas de perte de données, à condition que la
configuration (`slot.name`) reste identique.

```bash
# Deploy connector using Kafka Connect REST API
curl -X POST http://localhost:8083/connectors \
  -H "Content-Type: application/json" \
  -d @postgres-to-kafka-connector.json

# Check connector status
curl http://localhost:8083/connectors/postgres-to-kafka-connector/status

# Delete connector
curl -X DELETE http://localhost:8083/connectors/postgres-to-kafka-connector
```

## Purge de la table Outbox

Dans un système en production, la table `outbox_events` n’a pas vocation à conserver indéfiniment les messages. 
Une fois que Debezium a consommé et publié les événements, ceux-ci peuvent être supprimés pour éviter une croissance 
infinie de la table et une dégradation des performances.

Une stratégie courante consiste à implémenter un batch asynchrone (par exemple avec `@Scheduled` dans Spring) qui 
supprime les enregistrements plus vieux de quelques jours. Cette purge s'exécute en dehors du chemin critique et 
maintient la base de données légère.

# Application

Le service `OutboxEventPublisher` matérialise le point d’entrée unique pour la publication d’événements. Son rôle est
simple : sérialiser le payload Avro et persister l’événement dans la table Outbox.

L’utilisation de `@Transactional(propagation = Propagation.MANDATORY)` est un point clé. Elle impose que cette méthode
soit toujours appelée dans une transaction existante. Cela empêche toute publication “hors contexte métier” et garantit
l’atomicité.

La sérialisation via `KafkaAvroSerializer` est effectuée avant l’insertion en base. Si le schéma est invalide ou
incompatible, une exception est levée immédiatement, ce qui provoque le rollback complet de la transaction.

🫡 Ce comportement est volontairement strict : un événement invalide ne doit jamais atteindre la base.

```java

@Service
public class OutboxEventPublisher {

    private final OutboxJpaRepository outboxJpaRepository;
    private final KafkaAvroSerializer kafkaAvroSerializer;

    public OutboxEventPublisher(OutboxJpaRepository outboJpaRepository,
                                KafkaAvroSerializer kafkaAvroSerializer) {
        this.outboxJpaRepository = outboJpaRepository;
        this.kafkaAvroSerializer = kafkaAvroSerializer;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public <K, V extends SpecificRecord> void publish(String topic, K aggregateId, V payload, OutboxEventType eventType) {

        OutboxJPA outboxJPA = new OutboxJPA();

        outboxJPA.setTopic(topic);
        outboxJPA.setAggregateId(aggregateId.toString());
        outboxJPA.setEventType(eventType);
        outboxJPA.setPayload(kafkaAvroSerializer.serialize(topic, payload));
        outboxJPA.setCreatedAt(Instant.now());

        outboxJpaRepository.save(outboxJPA);
    }
}
```

La classe `OutboxProducer` introduit une abstraction intéressante. Elle permet de manipuler l’Outbox comme un producteur
Kafka classique, tout en restant dans le contexte transactionnel de la base.

Le typage générique `<K, V extends SpecificRecord>` impose l’utilisation d’objets Avro générés. Cela garantit, dès la
compilation, que seuls des payloads conformes au Schema Registry peuvent être utilisés.

Cette approche rapproche l’expérience développeur d’un `KafkaTemplate`, tout en conservant les garanties du pattern
Outbox. Le développeur publie un événement, sans se soucier du mécanisme sous-jacent.

```java
public class OutboxProducer<K, V extends SpecificRecord> {

    private final OutboxEventPublisher outboxEventPublisher;
    private final String topic;

    public OutboxProducer(OutboxEventPublisher outboxEventPublisher, String topic) {
        this.outboxEventPublisher = outboxEventPublisher;
        this.topic = topic;
    }

    /**
     * Publishes an event to the outbox table.
     * Must be called within an active transaction.
     *
     * @param aggregateId The aggregate ID (key) for the event
     * @param payload     The Avro object payload
     * @param eventType   The type of the event (e.g., CREATE, UPDATE, DELETE)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(K aggregateId, V payload, OutboxEventType eventType) {
        outboxEventPublisher.publish(topic, aggregateId, payload, eventType);
    }
}
```

L'utilisation explicite de l'`aggregateId` (souvent un UUID) est ici fondamentale car il est mappé par Debezium comme 
clé du message Kafka. Ce partitionnement par agrégat garantit l'ordre strict de traitement (*ordering*) et la 
cohérence des opérations pour une même entité côté consommateurs.

L’énumération `OutboxEventType` structure la nature des événements produits. Elle permet de distinguer clairement les
opérations métier (CREATE, UPDATE, DELETE) et d’enrichir le message avec une information sémantique exploitable côté
consommateurs.

```java
public enum OutboxEventType {
    CREATE,
    UPDATE,
    DELETE
}
```

L’exemple `TeamService` illustre parfaitement l’intégration du pattern dans un cas réel.

La création de l’entité et la publication de l’événement se font dans une **même transaction**. Si une erreur survient à
n’importe quel moment que ce soit lors de la **persistance** ou de la **sérialisation** Avro l’ensemble est **annulé**.

Le point important ici est l’ordre des opérations. L’entité est d’abord persistée, ce qui permet de récupérer un
identifiant stable. Cet identifiant est ensuite utilisé dans l’événement Avro, garantissant la cohérence entre la base
et le message.

Le développeur reste dans un modèle simple : il manipule des entités JPA et publie un événement. Toute la complexité
liée à Kafka, au schéma et au transport est encapsulée.

```java
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
        
        // 1. Business logic: create the aggregate
        final TeamEntity team = new TeamEntity();
        team.setName(teamName);
        team.setDescription(description);
        // ... (nested loop to add members omitted for clarity) ...

        // 2. Persist the entity within the transaction
        TeamEntity teamSaved = teamRepository.save(team);

        // 3. Build the Avro event from the generated strict contract
        TeamEvent teamEvent = TeamEvent.newBuilder()
                .setId(teamSaved.getId().toString())
                .setName(teamSaved.getName())
                .setDescription(teamSaved.getDescription())
                // ... (MemberEvent mapping omitted for clarity) ...
                .build();

        // 4. Publish the event through the Outbox within the same transaction
        teamEventProducer.publish(teamSaved.getId(), teamEvent, OutboxEventType.CREATE);
        
        return team;
    }
}
```

# Conclusion

L’association du pattern Outbox avec une approche “Strict Avro” permet de construire un pipeline événementiel **fiable,
déterministe et gouverné**. On ne se contente plus de résoudre le problème d’atomicité ; on garantit également la 
**qualité des événements** dès leur création.

Le principal avantage de cette approche réside dans son caractère **fail-fast**. Toute violation de contrat est détectée
immédiatement, au moment de la sérialisation. Cela évite la propagation d’erreurs dans le système et supprime une grande
partie des incidents liés au *Schema Drift*. Le schéma devient un élément exécutable, validé en continu, et non plus une
simple convention.

Cette rigueur s’accompagne d’une **chaîne de responsabilité claire**. L’application garantit la conformité des données,
la base assure l’atomicité, et Debezium se limite à un rôle de transport fiable. Cette séparation réduit la complexité
globale et améliore la lisibilité du système.

Sur le plan opérationnel, cette approche apporte également des bénéfices concrets. Le payload étant déjà sérialisé, il
n’y a aucun coût de transformation côté Kafka Connect. Le pipeline est plus direct, plus performant, et plus simple à
raisonner. La gouvernance des schémas via le Schema Registry permet par ailleurs de maîtriser l’évolution des contrats
dans le temps.

Cependant, ces gains s’accompagnent de compromis qu’il faut assumer.

Le premier est un **couplage fort avec l’écosystème Kafka et Avro**. La table Outbox ne contient plus une donnée métier
neutre, mais un message déjà formaté pour Kafka. Ce choix réduit la flexibilité et rend plus difficile une éventuelle
réutilisation dans un autre contexte.

Ce couplage se traduit aussi dans le code applicatif, qui devient dépendant du `KafkaAvroSerializer` et du Schema
Registry. On introduit ainsi une complexité supplémentaire côté développement, notamment pour les tests et les
environnements locaux.

Un autre inconvénient concerne la **perte de lisibilité des données en base**. Le stockage binaire rend les
investigations plus complexes et impose l’utilisation d’outils spécifiques pour décoder les messages. Là où un JSON
permettait un diagnostic rapide, cette approche demande davantage de discipline et d’outillage.

La dépendance au Schema Registry est également un point sensible. En cas d’indisponibilité, la production d’événements
est bloquée, ce qui peut impacter directement les transactions métier. Toutefois, il est important de noter que 
le **`KafkaAvroSerializer` dispose d'un cache local**. Si l'application a déjà enregistré ou récupéré le schéma en 
amont, elle pourra continuer à sérialiser et publier les messages même si le Schema Registry devient temporairement 
inaccessible.

Enfin, l’évolution des schémas devient plus encadrée en raison des **règles de compatibilité** imposées par le Schema 
Registry (Backward, Forward, Full). Si un développeur modifie un schéma de façon non-rétrocompatible 
(par exemple, en supprimant un champ obligatoire), la validation échouera avant même le déploiement. Si cela améliore 
la stabilité globale en empêchant le déploiement d'anomalies, cela impose aussi une **discipline forte** et peut 
ralentir certaines évolutions, en particulier dans des contextes où les modèles de données changent rapidement.

---

Au final, cette approche sera particulièrement pertinente dans des environnements de grande envergure où :

- la fiabilité des événements est absolument critique
- les consommateurs sont nombreux et indépendants
- les erreurs de schéma en production auraient un impact fort sur l'organisation

Le pari est donc un arbitrage assumé :

**accepter un surcoût de complexité et de couplage de l'écosystème, pour obtenir un système d'une fiabilité implacable, 
prévisible et sans aucune dérive de schéma.**

## Références

- Repo Github : https://github.com/Thieus/demo-pattern-outbox-with-dbz
- Debezium : https://debezium.io/documentation/reference/3.4/connectors/postgresql.html
- Debezium : https://debezium.io/documentation/reference/3.4/transformations/outbox-event-router.html

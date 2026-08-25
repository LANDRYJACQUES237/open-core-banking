-- Role d'execution, cree avant que Flyway ne migre afin que la migration V2 ait quelque
-- chose a qui accorder des droits. Reproduit la separation de production : celui qui migre
-- possede le schema, celui qui execute ne peut ni reecrire ni supprimer une notification
-- deja emise.
CREATE ROLE notification_app LOGIN PASSWORD 'app-secret';

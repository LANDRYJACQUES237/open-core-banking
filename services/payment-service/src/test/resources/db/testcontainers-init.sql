-- Role d'execution, cree avant que Flyway ne migre afin que la migration V4 ait quelque
-- chose a qui accorder des droits. Reproduit la separation de production : celui qui
-- migre possede le schema, celui qui execute recoit le minimum et ne peut notamment pas
-- modifier l'historique des transitions.
CREATE ROLE payment_app LOGIN PASSWORD 'app-secret';

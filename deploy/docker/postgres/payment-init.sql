-- Role d'execution du service payment.
--
-- Cree avant que Flyway ne migre : la migration lui accorde des droits, elle ne le cree
-- pas. Celui qui possede le schema et celui qui fait tourner l'application sont deux
-- identites distinctes, et c'est ce qui empeche l'application de retirer ses propres
-- garde-fous.
CREATE ROLE payment_app LOGIN PASSWORD 'app-secret';

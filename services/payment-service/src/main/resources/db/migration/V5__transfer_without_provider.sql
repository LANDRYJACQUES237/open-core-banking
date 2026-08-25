-- ---------------------------------------------------------------------------------------
-- Un transfert entre portefeuilles n'a pas d'operateur.
--
-- provider_code etait NOT NULL parce que les deux seuls types existants — encaissement et
-- decaissement — passent tous deux par un operateur Mobile Money. Le transfert, lui, ne
-- sort pas du grand livre : lui affecter un operateur serait inscrire une contre-verite
-- dans la base pour satisfaire une contrainte.
--
-- La colonne devient donc nullable, mais pas librement : une contrainte conditionnelle
-- remplace la contrainte inconditionnelle. Un encaissement ou un decaissement sans
-- operateur reste refuse, et un transfert qui en declarerait un l'est aussi. On ne relache
-- pas une regle, on l'exprime correctement.
-- ---------------------------------------------------------------------------------------

ALTER TABLE payment.payment_transaction
    ALTER COLUMN provider_code DROP NOT NULL;

ALTER TABLE payment.payment_transaction
    ADD CONSTRAINT ck_transaction_provider_matches_type
        CHECK (
            (type IN ('COLLECTION', 'DISBURSEMENT') AND provider_code IS NOT NULL)
                OR (type = 'TRANSFER' AND provider_code IS NULL)
            );

-- Meme raisonnement pour le numero masque : un transfert n'a ni payeur ni beneficiaire
-- joignable par telephone. La colonne etait deja nullable, aucune migration n'est
-- necessaire — c'est note ici pour que l'absence de contrainte ne passe pas pour un oubli.

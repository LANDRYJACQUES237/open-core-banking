package com.ocb.platform.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Serialisation des messages.
 *
 * <p>Un {@link ObjectMapper} dedie, distinct de celui utilise pour les API REST, parce que
 * les deux ont des exigences opposees. Une API peut evoluer sa representation ; un message
 * publie sur un bus est un contrat fige, relu potentiellement des mois plus tard par un
 * consommateur qui n'a pas ete redeploye entre-temps.
 *
 * <p>Deux reglages en decoulent :
 *
 * <ul>
 *   <li><b>Les dates sont des chaines ISO-8601</b>, pas des horodatages numeriques. Un
 *       nombre perd son fuseau et son unite : impossible de savoir en relisant un message
 *       archive s'il s'agit de secondes ou de millisecondes.
 *   <li><b>Un champ inconnu ne fait pas echouer la lecture.</b> C'est la condition meme de
 *       la retrocompatibilite : un producteur deja deploye avec un champ supplementaire ne
 *       doit pas casser un consommateur qui ne l'attend pas encore. Sans cela, tout ajout
 *       de champ imposerait un ordre de deploiement strict entre services.
 * </ul>
 */
public final class EventJson {

    private static final ObjectMapper MAPPER = create();

    private EventJson() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static ObjectMapper create() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                // Les champs nuls sont conserves : leur absence et leur presence a null
                // ne sont pas equivalentes pour un consommateur qui applique une mise a
                // jour partielle.
                .setSerializationInclusion(JsonInclude.Include.ALWAYS);
    }
}

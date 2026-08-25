package com.ocb.payment.adapter.web;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Identite de l'appelant, telle qu'elle borne la portee des cles d'idempotence.
 *
 * <p>Deux clients qui choisissent la meme cle — ce qui arrive des qu'un client utilise des
 * compteurs plutot que des identifiants aleatoires — ne doivent pas se voler mutuellement
 * leurs reponses. Le second recevrait la transaction du premier et croirait sa demande
 * prise en charge alors qu'elle aurait ete ignoree.
 *
 * <p>L'identite vient du jeton verifie, jamais d'un en-tete fourni par l'appelant : ce
 * dernier pourrait se declarer n'importe qui et lire les transactions d'un autre marchand
 * par simple collision de cle.
 */
final class CallerIdentity {

    private CallerIdentity() {
    }

    static String current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            // La chaine de securite refuse deja les requetes non authentifiees. Si on
            // arrive ici, c'est qu'une regle a ete relachee par erreur : mieux vaut
            // echouer que d'agreger silencieusement tous les appelants sous une meme
            // portee.
            throw new IllegalStateException(
                    "Aucune identite authentifiee : la portee d'idempotence serait partagee");
        }
        return authentication.getName();
    }
}

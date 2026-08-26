package com.ocb.platform.kafka;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.kafka.core.KafkaAdmin;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Sonde de disponibilite du courtier.
 *
 * <p>Spring Boot n'en fournit pas, alors qu'il en fournit une pour la base. L'absence
 * n'est pas anodine ici : trois services de la plateforme sont des consommateurs, et un
 * consommateur coupe du courtier ne traite plus rien tout en repondant joyeusement aux
 * requetes HTTP.
 *
 * <p><b>Ou elle est utilisee.</b> Dans la <b>readiness</b> uniquement, jamais dans la
 * liveness. Un pod dont la liveness dependrait du courtier redemarrerait en boucle pendant
 * une coupure Kafka — ce qui n'y changerait rien et ajouterait une panne a une panne.
 * Retire du service, en revanche, est le comportement correct : le trafic va ailleurs et
 * revient quand le courtier revient.
 *
 * <p><b>Le delai est borne, et c'est le point critique.</b> Une sonde de disponibilite est
 * interrogee toutes les quelques secondes par l'orchestrateur. Sans delai maximal, un
 * courtier qui accepte la connexion sans repondre — le cas classique — immobiliserait un
 * fil de requete a chaque interrogation, jusqu'a epuisement du pool. Le service tomberait
 * alors pour une raison sans rapport avec la question posee.
 *
 * <p><b>Aucun cache, volontairement.</b> Une reponse mise en cache retarde la detection du
 * retour a la normale autant que celle de la panne. Un appel borne toutes les dix secondes
 * coute moins cher que le raisonnement necessaire pour choisir une duree de cache
 * defendable.
 */
public class KafkaHealthIndicator extends AbstractHealthIndicator implements DisposableBean {

    private final Map<String, Object> adminProperties;
    private final Duration timeout;

    /**
     * Client d'administration cree une seule fois, a la premiere interrogation.
     *
     * <p>Le creer au demarrage reintroduirait une dependance a l'ordre de demarrage des
     * composants ; en creer un a chaque interrogation ferait naitre un fil reseau toutes
     * les dix secondes.
     */
    private volatile AdminClient admin;

    public KafkaHealthIndicator(KafkaAdmin kafkaAdmin, Duration timeout) {
        super("Courtier Kafka injoignable");
        this.adminProperties = new HashMap<>(kafkaAdmin.getConfigurationProperties());
        this.timeout = timeout;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) throws Exception {
        Collection<org.apache.kafka.common.Node> nodes = admin()
                .describeCluster(new DescribeClusterOptions().timeoutMs((int) timeout.toMillis()))
                .nodes()
                .get(timeout.toMillis(), TimeUnit.MILLISECONDS);

        if (nodes == null || nodes.isEmpty()) {
            // Un cluster qui repond sans annoncer de noeud n'est pas utilisable. Le cas
            // parait theorique ; le traiter coute une ligne, et le confondre avec un
            // succes rendrait le service pret alors qu'il ne peut rien consommer.
            builder.down().withDetail("nodes", 0);
            return;
        }
        builder.up().withDetail("nodes", nodes.size());
    }

    private AdminClient admin() {
        AdminClient current = admin;
        if (current == null) {
            synchronized (this) {
                current = admin;
                if (current == null) {
                    current = AdminClient.create(adminProperties);
                    admin = current;
                }
            }
        }
        return current;
    }

    @Override
    public void destroy() {
        AdminClient current = admin;
        if (current != null) {
            current.close(Duration.ofSeconds(1));
        }
    }
}

package com.ocb.platform.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rend le contrat d'evenements opposable.
 *
 * <p>Sans ce test, {@code contracts/events/} serait de la documentation : elle divergerait
 * du code au premier refactoring, et personne ne s'en apercevrait avant qu'un consommateur
 * d'un autre service ne casse en production.
 *
 * <p>Trois verifications, qui se completent :
 *
 * <ol>
 *   <li>chaque type declare dans {@link EventTypes} a un schema, et reciproquement —
 *       ce qui interdit d'ajouter un evenement sans le contractualiser ;
 *   <li>une instance reelle de chaque charge utile valide contre son schema ;
 *   <li>l'enveloppe complete valide contre son propre schema.
 * </ol>
 */
class EventContractTest {

    private static final Path CONTRACTS = Path.of("..", "..", "contracts", "events");
    private static final ObjectMapper MAPPER = EventJson.mapper();

    private static final JsonSchemaFactory FACTORY =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    /** Un exemple realiste par type. C'est ce qui est confronte au schema. */
    private static final Map<String, Object> SAMPLES = Map.ofEntries(
            Map.entry(EventTypes.PROVIDER_COLLECTION_EXECUTE,
            new Payloads.ProviderCollectionExecute(
                    "0f4d3d7e-1c9a-4a2b-9f3e-2b7c1d5e6a8f", "MTN_MOMO", "10000", "XAF",
                    "+237670000001", "TX-001", "idem-001")),
            Map.entry(EventTypes.PROVIDER_OPERATION_ACCEPTED,
            new Payloads.ProviderOperationAccepted(
                    "0f4d3d7e-1c9a-4a2b-9f3e-2b7c1d5e6a8f", "MTN_MOMO", "MTN-REF-1", Instant.now())),
            Map.entry(EventTypes.PROVIDER_OPERATION_SUCCEEDED,
            new Payloads.ProviderOperationSucceeded(
                    "0f4d3d7e-1c9a-4a2b-9f3e-2b7c1d5e6a8f", "MTN_MOMO", "MTN-REF-1",
                    "150", "XAF", Instant.now(), "CALLBACK")),
            Map.entry(EventTypes.PROVIDER_OPERATION_FAILED,
            new Payloads.ProviderOperationFailed(
                    "0f4d3d7e-1c9a-4a2b-9f3e-2b7c1d5e6a8f", "MTN_MOMO", null,
                    "INSUFFICIENT_FUNDS", "Solde insuffisant", "POLL")),
            Map.entry(EventTypes.PROVIDER_OPERATION_UNRESOLVED,
            new Payloads.ProviderOperationUnresolved(
                    "0f4d3d7e-1c9a-4a2b-9f3e-2b7c1d5e6a8f", "MTN_MOMO", "MTN-REF-1", 12, "PENDING")),
            Map.entry(EventTypes.PAYMENT_COLLECTION_REQUESTED,
            new Payloads.PaymentCollectionRequested(
                    "0f4d3d7e-1c9a-4a2b-9f3e-2b7c1d5e6a8f", "TX-001", "10000", "XAF",
                    "100", "2100.wallet-c", "MTN_MOMO", "+2376****0001")),
            Map.entry(EventTypes.PAYMENT_COLLECTION_COMPLETED,
            new Payloads.PaymentCollectionCompleted(
                    "0f4d3d7e-1c9a-4a2b-9f3e-2b7c1d5e6a8f", "TX-001", "10000", "XAF",
                    "100", "150", "2100.wallet-c", "JE-20260821-AB12CD34", "+2376****0001")),
            Map.entry(EventTypes.PAYMENT_COLLECTION_FAILED,
            new Payloads.PaymentCollectionFailed(
                    "0f4d3d7e-1c9a-4a2b-9f3e-2b7c1d5e6a8f", "TX-001", "10000", "XAF",
                    "2100.wallet-c", "PROVIDER_DECLINED", "Solde insuffisant",
                    "+2376****0001")),
            Map.entry(EventTypes.PAYMENT_MANUAL_REVIEW_REQUIRED,
            new Payloads.PaymentManualReviewRequired(
                    "0f4d3d7e-1c9a-4a2b-9f3e-2b7c1d5e6a8f", "TX-001", "MANUAL_REVIEW",
                    "budget de polling epuise sans statut definitif")),
            Map.entry(EventTypes.PROVIDER_DISBURSEMENT_EXECUTE,
                    new Payloads.ProviderDisbursementExecute(
                            "0f4d3d7e-1c9a-4a2b-9f3e-2b7c1d5e6a8f", "MTN_MOMO", "5000", "XAF",
                            "+237670000001", "TX-002", "disbursement:0f4d3d7e")),
            Map.entry(EventTypes.PAYMENT_DISBURSEMENT_REQUESTED,
                    new Payloads.PaymentDisbursementRequested(
                            "0f4d3d7e-1c9a-4a2b-9f3e-2b7c1d5e6a8f", "TX-002", "5000", "XAF",
                            "50", "2100.wallet-c", "MTN_MOMO", "JE-20260825-RESERVE1",
                            "+2376****0001")),
            Map.entry(EventTypes.PAYMENT_DISBURSEMENT_COMPLETED,
                    new Payloads.PaymentDisbursementCompleted(
                            "0f4d3d7e-1c9a-4a2b-9f3e-2b7c1d5e6a8f", "TX-002", "5000", "XAF",
                            "50", "25", "2100.wallet-c", "JE-20260825-SETTLE01",
                            "+2376****0001")),
            Map.entry(EventTypes.PAYMENT_TRANSFER_COMPLETED,
                    new Payloads.PaymentTransferCompleted(
                            "0f4d3d7e-1c9a-4a2b-9f3e-2b7c1d5e6a8f", "TX-003", "2000", "XAF",
                            "20", "2100.wallet-a", "2100.wallet-b", "JE-20260825-TRANSF1")),
            Map.entry(EventTypes.PAYMENT_DISBURSEMENT_REVERSED,
                    new Payloads.PaymentDisbursementReversed(
                            "0f4d3d7e-1c9a-4a2b-9f3e-2b7c1d5e6a8f", "TX-002", "5000", "XAF",
                            "2100.wallet-c", "JE-20260825-RESERVE1", "JE-20260825-REVERSE1",
                            "PROVIDER_DECLINED", "Beneficiaire inconnu", "+2376****0001")));

    static Stream<String> declaredEventTypes() {
        return SAMPLES.keySet().stream().sorted();
    }

    @Test
    @DisplayName("le catalogue de schemas et les types declares en Java se correspondent exactement")
    void schemasAndTypesMatch() throws IOException {
        Set<String> schemaTypes = payloadDefinitionNames();
        Set<String> javaTypes = SAMPLES.keySet();

        assertThat(schemaTypes)
                .as("un type sans schema ne serait contractualise nulle part")
                .containsAll(javaTypes);
        assertThat(javaTypes)
                .as("un schema sans type Java est un contrat que personne ne produit")
                .containsAll(schemaTypes);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("declaredEventTypes")
    @DisplayName("chaque charge utile valide contre son schema")
    void payloadMatchesItsSchema(String eventType) throws IOException {
        JsonNode payload = MAPPER.valueToTree(SAMPLES.get(eventType));

        JsonNode catalog = MAPPER.readTree(Files.readString(CONTRACTS.resolve("payloads.schema.json")));
        JsonNode definition = catalog.get("$defs").get(eventType);
        assertThat(definition).as("schema absent pour %s", eventType).isNotNull();

        // Le schema est extrait puis re-ancre avec les $defs du catalogue, afin que les
        // $ref internes ($defs/amount, $defs/currency) restent resolubles hors contexte.
        var standalone = MAPPER.createObjectNode();
        standalone.setAll((com.fasterxml.jackson.databind.node.ObjectNode) definition);
        standalone.set("$defs", catalog.get("$defs"));

        JsonSchema schema = FACTORY.getSchema(standalone);
        Set<ValidationMessage> violations = schema.validate(payload);

        assertThat(violations)
                .as("%s ne respecte pas son contrat : %s", eventType, violations)
                .isEmpty();
    }

    @Test
    @DisplayName("l'enveloppe complete valide contre son schema")
    void envelopeMatchesItsSchema() throws IOException {
        EventEnvelope envelope = EventEnvelope.of(
                EventTypes.PAYMENT_COLLECTION_COMPLETED,
                "PaymentTransaction",
                "0f4d3d7e-1c9a-4a2b-9f3e-2b7c1d5e6a8f",
                "corr-123",
                "cause-456",
                "payment-service",
                SAMPLES.get(EventTypes.PAYMENT_COLLECTION_COMPLETED));

        JsonSchema schema = FACTORY.getSchema(
                MAPPER.readTree(Files.readString(CONTRACTS.resolve("envelope.schema.json"))));

        Set<ValidationMessage> violations = schema.validate(MAPPER.valueToTree(envelope));

        assertThat(violations).as("%s", violations).isEmpty();
    }

    @Test
    @DisplayName("un montant en nombre JSON serait refuse par le contrat")
    void numericAmountsAreRejected() throws IOException {
        // Verifie que la regle n'est pas seulement une convention de nommage : le schema
        // refuse activement un montant numerique, qui serait parse en double par un client.
        JsonNode payload = MAPPER.readTree("""
                {"transactionId":"0f4d3d7e-1c9a-4a2b-9f3e-2b7c1d5e6a8f","externalRef":"TX-001",
                 "amount":10000,"currency":"XAF","platformFee":"100",
                 "walletAccountRef":"2100.wallet-c","providerCode":"MTN_MOMO","maskedMsisdn":null}
                """);

        JsonNode catalog = MAPPER.readTree(Files.readString(CONTRACTS.resolve("payloads.schema.json")));
        var standalone = MAPPER.createObjectNode();
        standalone.setAll((com.fasterxml.jackson.databind.node.ObjectNode)
                catalog.get("$defs").get(EventTypes.PAYMENT_COLLECTION_REQUESTED));
        standalone.set("$defs", catalog.get("$defs"));

        assertThat(FACTORY.getSchema(standalone).validate(payload)).isNotEmpty();
    }

    private static Set<String> payloadDefinitionNames() throws IOException {
        JsonNode defs = MAPPER
                .readTree(Files.readString(CONTRACTS.resolve("payloads.schema.json")))
                .get("$defs");

        // amount, currency et maskedMsisdn sont des fragments reutilisables, pas des
        // types d'evenements : on les reconnait au fait qu'ils ne contiennent pas de point.
        Set<String> names = new java.util.TreeSet<>();
        defs.fieldNames().forEachRemaining(name -> {
            if (name.contains(".")) {
                names.add(name);
            }
        });
        return names;
    }
}

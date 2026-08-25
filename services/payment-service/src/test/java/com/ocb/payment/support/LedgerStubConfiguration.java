package com.ocb.payment.support;

import com.ocb.payment.domain.port.LedgerPort;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class LedgerStubConfiguration {

    /**
     * Prend le pas sur {@code LedgerRestClient}, qui reste construit.
     *
     * <p>Le laisser dans le contexte n'est pas un oubli : sa construction exerce toute la
     * chaine OAuth2 du client, si bien qu'une configuration cassee de ce cote fait echouer
     * ces tests aussi, au lieu de n'apparaitre qu'en CI.
     */
    @Bean
    @Primary
    public LedgerPort ledgerStub() {
        return new LedgerStub();
    }
}

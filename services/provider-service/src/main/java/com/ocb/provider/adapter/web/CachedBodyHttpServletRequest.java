package com.ocb.provider.adapter.web;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Requete dont le corps peut etre lu deux fois.
 *
 * <p>Necessaire parce que le corps d'une requete HTTP est un flux : le lire le consomme.
 * Or la signature doit etre verifiee sur les octets bruts <b>avant</b> que Jackson ne les
 * analyse, et Jackson doit ensuite pouvoir les lire a son tour.
 *
 * <p>Rejouer une representation reserialisee ne conviendrait pas : un espace, un ordre de
 * champs ou une notation numerique differente suffiraient a invalider une signature
 * pourtant legitime.
 */
final class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    CachedBodyHttpServletRequest(HttpServletRequest request, byte[] body) {
        super(request);
        this.body = body;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream source = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return source.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                throw new UnsupportedOperationException("Lecture asynchrone non supportee");
            }

            @Override
            public int read() throws IOException {
                return source.read();
            }
        };
    }

    @Override
    public java.io.BufferedReader getReader() {
        return new java.io.BufferedReader(
                new java.io.InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
    }
}

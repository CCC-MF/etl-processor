/*
 * This file is part of ETL-Processor
 *
 * Copyright (c) 2024  Comprehensive Cancer Center Mainfranken, Datenintegrationszentrum Philipps-Universität Marburg and Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package dev.dnpm.etl.processor.pseudonym;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import dev.dnpm.etl.processor.config.GPasConfigProperties;
import org.apache.commons.lang3.StringUtils;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.BasicHttpClientConnectionManager;
import org.apache.hc.client5.http.socket.ConnectionSocketFactory;
import org.apache.hc.client5.http.socket.PlainConnectionSocketFactory;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.core5.http.config.Registry;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Parameters.ParametersParameterComponent;
import org.hl7.fhir.r4.model.StringType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestTemplate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;

public class GpasPseudonymGenerator implements Generator {

    private final static FhirContext r4Context = FhirContext.forR4();
    private final String gPasUrl;
    private final String psnTargetDomain;
    private final HttpHeaders httpHeader;
    private final RetryTemplate retryTemplate;
    private final Logger log = LoggerFactory.getLogger(GpasPseudonymGenerator.class);

    private final RestTemplate restTemplate;

    private SSLContext customSslContext;

    public GpasPseudonymGenerator(GPasConfigProperties gpasCfg, RetryTemplate retryTemplate) {
        this.retryTemplate = retryTemplate;
        this.restTemplate = getRestTemplete();

        this.gPasUrl = gpasCfg.getUri();
        this.psnTargetDomain = gpasCfg.getTarget();
        httpHeader = getHttpHeaders(gpasCfg.getUsername(), gpasCfg.getPassword());

        try {
            if (StringUtils.isNotBlank(gpasCfg.getSslCaLocation())) {
                customSslContext = getSslContext(gpasCfg.getSslCaLocation());
                log.warn(String.format("%s has been initialized with SSL certificate %s. This is deprecated in favor of including Root CA.",
                    this.getClass().getName(), gpasCfg.getSslCaLocation()));
            }
        } catch (IOException | KeyManagementException | KeyStoreException | CertificateException |
                 NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }

        log.debug(String.format("%s has been initialized", this.getClass().getName()));

    }

    @Override
    public String generate(String id) {
        var gPasRequestBody = getGpasRequestBody(id);
        var responseEntity = getGpasPseudonym(gPasRequestBody);
        var gPasPseudonymResult = (Parameters) r4Context.newJsonParser()
            .parseResource(responseEntity.getBody());

        return unwrapPseudonym(gPasPseudonymResult);
    }

    @NotNull
    public static String unwrapPseudonym(Parameters gPasPseudonymResult) {
        final var parameters = gPasPseudonymResult.getParameter().stream().findFirst();

        if (parameters.isEmpty()) {
            throw new PseudonymRequestFailed("Empty HL7 parameters, cannot find first one");
        }

        final var identifier = (Identifier) parameters.get().getPart().stream()
            .filter(a -> a.getName().equals("pseudonym"))
            .findFirst()
            .orElseGet(ParametersParameterComponent::new).getValue();

        // pseudonym
        return sanitizeValue(identifier.getValue());
    }

    /**
     * Allow only filename friendly values
     *
     * @param psnValue GAPS pseudonym value
     * @return cleaned up value
     */
    public static String sanitizeValue(String psnValue) {
        // pattern to match forbidden characters
        String forbiddenCharsRegex = "[\\\\/:*?\"<>|;]";

        // Replace all forbidden characters with underscores
        return psnValue.replaceAll(forbiddenCharsRegex, "_");
    }


    @NotNull
    protected ResponseEntity<String> getGpasPseudonym(String gPasRequestBody) {

        HttpEntity<String> requestEntity = new HttpEntity<>(gPasRequestBody, this.httpHeader);
        ResponseEntity<String> responseEntity;

        try {
            responseEntity = retryTemplate.execute(
                ctx -> restTemplate.exchange(gPasUrl, HttpMethod.POST, requestEntity,
                    String.class));

            if (responseEntity.getStatusCode().is2xxSuccessful()) {
                log.debug("API request succeeded. Response: {}", responseEntity.getStatusCode());
            } else {
                log.warn("API request unsuccessful. Response: {}", requestEntity.getBody());
                throw new PseudonymRequestFailed("API request unsuccessful gPas unsuccessful.");
            }

            return responseEntity;
        } catch (Exception unexpected) {
            throw new PseudonymRequestFailed(
                "API request due unexpected error unsuccessful gPas unsuccessful.", unexpected);
        }
    }

    protected String getGpasRequestBody(String id) {
        var requestParameters = new Parameters();
        requestParameters.addParameter().setName("target")
            .setValue(new StringType().setValue(psnTargetDomain));
        requestParameters.addParameter().setName("original")
            .setValue(new StringType().setValue(id));
        final IParser iParser = r4Context.newJsonParser();
        return iParser.encodeResourceToString(requestParameters);
    }

    @NotNull
    protected HttpHeaders getHttpHeaders(String gPasUserName, String gPasPassword) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        if (StringUtils.isBlank(gPasUserName) || StringUtils.isBlank(gPasPassword)) {
            return headers;
        }

        String authHeader = gPasUserName + ":" + gPasPassword;
        byte[] authHeaderBytes = authHeader.getBytes();
        byte[] encodedAuthHeaderBytes = Base64.getEncoder().encode(authHeaderBytes);
        String encodedAuthHeader = new String(encodedAuthHeaderBytes);

        if (StringUtils.isNotBlank(gPasUserName) && StringUtils.isNotBlank(gPasPassword)) {
            headers.set("Authorization", "Basic " + encodedAuthHeader);
        }

        return headers;
    }

    /**
     * Read SSL root certificate and return SSLContext
     *
     * @param certificateLocation file location to root certificate (PEM)
     * @return initialized SSLContext
     * @throws IOException              file cannot be read
     * @throws CertificateException     in case we have an invalid certificate of type X.509
     * @throws KeyStoreException        keystore cannot be initialized
     * @throws NoSuchAlgorithmException missing trust manager algorithmus
     * @throws KeyManagementException   key management failed at init SSLContext
     */
    @Nullable
    protected SSLContext getSslContext(String certificateLocation)
        throws IOException, CertificateException, KeyStoreException, KeyManagementException, NoSuchAlgorithmException {

        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());

        FileInputStream fis = new FileInputStream(certificateLocation);
        X509Certificate ca = (X509Certificate) CertificateFactory.getInstance("X.509")
            .generateCertificate(new BufferedInputStream(fis));

        ks.load(null, null);
        ks.setCertificateEntry(Integer.toString(1), ca);

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, tmf.getTrustManagers(), null);

        return sslContext;
    }

    protected RestTemplate getRestTemplete() {
        if (customSslContext == null) {
            return new RestTemplate();
        }
        final var sslsf = new SSLConnectionSocketFactory(customSslContext);
        final Registry<ConnectionSocketFactory> socketFactoryRegistry = RegistryBuilder.<ConnectionSocketFactory>create()
            .register("https", sslsf).register("http", new PlainConnectionSocketFactory()).build();

        final BasicHttpClientConnectionManager connectionManager = new BasicHttpClientConnectionManager(
            socketFactoryRegistry);
        final CloseableHttpClient httpClient = HttpClients.custom()
            .setConnectionManager(connectionManager).build();

        final HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory(
            httpClient);
        return new RestTemplate(requestFactory);
    }
}

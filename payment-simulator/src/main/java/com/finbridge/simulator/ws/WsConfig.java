package com.finbridge.simulator.ws;

import java.time.Clock;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.ws.config.annotation.WsConfigurer;
import org.springframework.ws.server.EndpointInterceptor;
import org.springframework.ws.wsdl.wsdl11.DefaultWsdl11Definition;
import org.springframework.xml.xsd.SimpleXsdSchema;
import org.springframework.xml.xsd.XsdSchema;

/**
 * Contract-first SOAP: the XSD is written by hand, and everything else is derived from it.
 *
 *   endpoint  POST /ws                 (path set by spring.webservices.path)
 *   WSDL      GET  /ws/payments.wsdl   (generated from the XSD; the bean name gives the file name)
 *   schema    GET  /ws/paymentsSchema.xsd
 */
@Configuration
public class WsConfig implements WsConfigurer {

    public static final String NAMESPACE = "http://finbridge.com/payments/v1";

    @Bean
    public XsdSchema paymentsSchema() {
        return new SimpleXsdSchema(new ClassPathResource("xsd/payments.xsd"));
    }

    @Bean(name = "payments")
    public DefaultWsdl11Definition paymentsWsdl(XsdSchema paymentsSchema) {
        DefaultWsdl11Definition wsdl = new DefaultWsdl11Definition();
        wsdl.setPortTypeName("PaymentsPort");
        wsdl.setLocationUri("/ws");
        wsdl.setTargetNamespace(NAMESPACE);
        wsdl.setSchema(paymentsSchema);
        return wsdl;
    }

    @Bean
    public Jaxb2Marshaller paymentsMarshaller() {
        Jaxb2Marshaller marshaller = new Jaxb2Marshaller();
        marshaller.setContextPath("com.finbridge.payments.v1");
        return marshaller;
    }

    /** Every request and every response is checked against the XSD before and after the endpoint runs. */
    @Bean
    public SchemaValidationInterceptor schemaValidationInterceptor(XsdSchema paymentsSchema,
                                                                 Jaxb2Marshaller paymentsMarshaller) {
        SchemaValidationInterceptor interceptor = new SchemaValidationInterceptor(paymentsMarshaller);
        interceptor.setXsdSchema(paymentsSchema);
        interceptor.setValidateRequest(true);
        interceptor.setValidateResponse(true);
        return interceptor;
    }

    private final org.springframework.beans.factory.ObjectProvider<SchemaValidationInterceptor> validation;

    public WsConfig(org.springframework.beans.factory.ObjectProvider<SchemaValidationInterceptor> validation) {
        this.validation = validation;
    }

    @Override
    public void addInterceptors(List<EndpointInterceptor> interceptors) {
        interceptors.add(validation.getObject());
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}

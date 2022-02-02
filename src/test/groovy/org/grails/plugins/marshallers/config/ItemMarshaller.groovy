package org.grails.plugins.marshallers.config

import grails.converters.XML
import org.grails.web.converters.exceptions.ConverterException
import org.grails.web.converters.marshaller.NameAwareMarshaller
import org.grails.web.converters.marshaller.ObjectMarshaller

class ItemMarshaller implements ObjectMarshaller<XML>, NameAwareMarshaller {

    @Override
    public boolean supports(Object object) {
        return object.class == Item
    }

    @Override
    public void marshalObject(Object value, XML xml)    throws ConverterException {
        xml.attribute('name', value.name)
    }

    @Override
    public String getElementName(Object value) {
        return 'xxx'
    }
}
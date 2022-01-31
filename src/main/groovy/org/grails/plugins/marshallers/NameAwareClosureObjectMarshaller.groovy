package org.grails.plugins.marshallers

import org.grails.web.converters.Converter
import org.grails.web.converters.marshaller.ClosureObjectMarshaller
import org.grails.web.converters.marshaller.NameAwareMarshaller

class NameAwareClosureObjectMarshaller<T extends Converter> extends ClosureObjectMarshaller<T> implements NameAwareMarshaller {

    def elementName

    def NameAwareClosureObjectMarshaller(Class clazz, String elementName, Closure closure) {
        super(clazz, closure)
        this.elementName = elementName
    }

    String getElementName(Object o) {
        elementName
    }

}
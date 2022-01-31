package org.grails.plugins.marshallers

import grails.converters.XML
import groovy.util.logging.Slf4j
import org.grails.core.artefact.DomainClassArtefactHandler
import grails.core.GrailsApplication
import grails.util.GrailsClassUtils as GCU
import grails.core.GrailsDomainClass
import grails.core.support.proxy.EntityProxyHandler
import grails.core.support.proxy.ProxyHandler
import org.grails.datastore.mapping.model.PersistentEntity
import org.grails.datastore.mapping.model.PersistentProperty
import org.grails.datastore.mapping.model.types.Association
import org.grails.datastore.mapping.model.types.Embedded
import org.grails.datastore.mapping.model.types.ManyToOne
import org.grails.datastore.mapping.model.types.OneToOne
import org.grails.web.converters.ConverterUtil
import org.grails.web.converters.exceptions.ConverterException
import org.grails.web.converters.marshaller.NameAwareMarshaller
import org.grails.web.converters.marshaller.ObjectMarshaller
import org.grails.plugins.marshallers.config.MarshallingConfig
import org.grails.plugins.marshallers.config.MarshallingConfigPool
import org.springframework.beans.BeanWrapper
import org.springframework.beans.BeanWrapperImpl

/**
 *
 * @author dhalupa
 *
 */
@Slf4j
class GenericDomainClassXMLMarshaller implements ObjectMarshaller<XML>, NameAwareMarshaller {

    private ProxyHandler proxyHandler
    private GrailsApplication application
    private MarshallingConfigPool configPool
    private static MarshallingContext marshallingContext = new MarshallingContext();


    private static Map<Class, Class> attributeEditors = new HashMap<Class, Class>()

    public GenericDomainClassXMLMarshaller(ProxyHandler proxyHandler, GrailsApplication application, MarshallingConfigPool configPool) {
        this.proxyHandler = proxyHandler
        this.application = application
        this.configPool = configPool
    }

    @Override
    public boolean supports(Object object) {
        def clazz = proxyHandler.unwrapIfProxy(object).getClass()
        boolean supports = configPool.get(clazz) != null

        if (log.debugEnabled) {
            log.debug("Support for $clazz is $supports")
        }

        return supports
    }

    @Override
    public void marshalObject(Object v, XML xml) throws ConverterException {
        if (log.debugEnabled) {
            log.debug("Marshalling of $v started")
        }

        def value = proxyHandler.unwrapIfProxy(v)
        Class clazz = value.getClass()

        //GrailsDomainClass domainClass = application.getArtefact(DomainClassArtefactHandler.TYPE, clazz.getName())
        PersistentEntity persistentEntity = application.mappingContext.getPersistentEntity(clazz.getName())

        MarshallingConfig marshallingConfig = configPool.get(clazz)

        BeanWrapper beanWrapper = new BeanWrapperImpl(value)

        if (marshallingConfig.shouldOutputIdentifier) {
            if (marshallingConfig.identifier) {
                if (marshallingConfig.identifier.size() == 1 && marshallingConfig.identifier[0] instanceof Closure) {
                    marshallingConfig.identifier[0].call(value, xml)
                } else {
                    marshallingConfig.identifier.each {
                        def val = beanWrapper.getPropertyValue(it)
                        if (val != null) {
                            xml.attribute(it, val.toString())
                        }
                    }
                }
            } else {
                //GrailsDomainClassProperty id = domainClass.getIdentifier()
                PersistentProperty id = persistentEntity.getIdentity()

                Object idValue = beanWrapper.getPropertyValue(id.getName())

                if (idValue != null) {
                    xml.attribute("id", String.valueOf(idValue))
                }
            }
        }
        if (marshallingConfig.shouldOutputVersion) {
            PersistentProperty versionProperty = persistentEntity.getVersion()

            Object versionValue = beanWrapper.getPropertyValue(versionProperty.getName())

            xml.attribute("version", String.valueOf(versionValue))
        }

        if (marshallingConfig.shouldOutputClass) {
            xml.attribute("class", clazz.getName())
        }

        marshallingConfig.attribute?.each { prop ->
            if (log.debugEnabled) {
                log.debug("Trying to write field as xml attribute: $prop on $value")
            }

            Object val = beanWrapper.getPropertyValue(prop)

            if (val != null) {
                def editorEntry = attributeEditors.find {
                    it.key.isAssignableFrom(val.getClass())
                }

                if (editorEntry) {
                    def editor = editorEntry.value.newInstance()
                    editor.setValue(val)
                    xml.attribute(prop, editor.getAsText())
                } else {
                    xml.attribute(prop, val.toString())
                }
            }
        }

        boolean includeMode = false

        if (marshallingConfig.include?.size() > 0) {
            includeMode = true
        }

        //GrailsDomainClassProperty[] properties = domainClass.getPersistentProperties()
        List<PersistentProperty> properties = persistentEntity.getPersistentProperties()

        //for (GrailsDomainClassProperty property : properties) {
        for (PersistentProperty property : properties) {
            if (
                !marshallingConfig.identifier?.contains(property.getName()) &&
                !marshallingConfig.attribute?.contains(property.getName()) &&
                (
                        !includeMode && !marshallingConfig.ignore?.contains(property.getName()) ||
                        includeMode && marshallingConfig.include?.contains(property.getName())
                )
            ) {
                def serializers = marshallingConfig?.serializer

                Object val = beanWrapper.getPropertyValue(property.getName())

                if (serializers && serializers[property.name]) {
                    xml.startNode(property.name)
                    serializers[property.name].call(val, xml)
                    xml.end()
                } else {
                    if (val != null) {
                        if (log.debugEnabled) {
                            log.debug("Trying to write field as xml element: $property.name on $value")
                        }

                        writeElement(xml, property, beanWrapper, marshallingConfig)
                    }
                }
            }
        }

        if (marshallingConfig.virtual) {
            marshallingConfig.virtual.each { prop, callable ->
                xml.startNode(prop)
                def cl = marshallingConfig.virtual[prop]
                if (cl.maximumNumberOfParameters == 2) {
                    cl.call(value, xml)
                } else {
                    cl.call(value, xml, marshallingContext)
                }

                xml.end()
            }
        }
    }


    //private writeElement(XML xml, GrailsDomainClassProperty property, BeanWrapper beanWrapper, MarshallingConfig marshallingConfig) {
    private writeElement(XML xml, PersistentProperty property, BeanWrapper beanWrapper, MarshallingConfig marshallingConfig) {
        xml.startNode(property.getName())

        //if (!property.isAssociation()) {
        if (!(property instanceof Association)) {
            // Write non-relation property
            Object val = beanWrapper.getPropertyValue(property.getName())
            xml.convertAnother(val)
        } else {
            Object referenceObject = beanWrapper.getPropertyValue(property.getName())

            if (marshallingConfig.deep?.contains(property.getName())) {
                renderDeep(referenceObject, xml)
            } else {
                if (referenceObject != null) {
                    //GrailsDomainClass referencedDomainClass = property.getReferencedDomainClass()
                    PersistentEntity referencedDomainClass = property.getAssociatedEntity()

                    // Embedded are now always fully rendered
                    if (referencedDomainClass == null || property.isEmbedded() || property.getType().isEnum()) {
                        xml.convertAnother(referenceObject)
                    //} else if (property.isOneToOne() || property.isManyToOne() || property.isEmbedded()) {
                    } else if (property instanceof OneToOne || property instanceof ManyToOne || property instanceof Embedded) {
                        asShortObject(referenceObject, xml, referencedDomainClass.getIdentity(), referencedDomainClass)
                    } else {
                        PersistentProperty referencedIdProperty = referencedDomainClass.getIdentity()

                        @SuppressWarnings("unused")
                        String refPropertyName = referencedDomainClass.getPropertyName()

                        if (referenceObject instanceof Collection) {
                            Collection o = (Collection) referenceObject
                            for (Object el : o) {
                                xml.startNode(xml.getElementName(el))
                                asShortObject(el, xml, referencedIdProperty, referencedDomainClass)
                                xml.end()
                            }
                        } else if (referenceObject instanceof Map) {
                            Map<Object, Object> map = (Map<Object, Object>) referenceObject

                            for (Map.Entry<Object, Object> entry : map.entrySet()) {
                                String key = String.valueOf(entry.getKey())
                                Object o = entry.getValue()
                                xml.startNode("entry").attribute("key", key)
                                asShortObject(o, xml, referencedIdProperty, referencedDomainClass)
                                xml.end()
                            }
                        }
                    }
                }
            }
        }
        xml.end()
    }

    private void renderDeep(referenceObject, XML xml) {
        if (referenceObject != null) {

            referenceObject = proxyHandler.unwrapIfProxy(referenceObject)

            if (referenceObject instanceof SortedMap) {
                referenceObject = new TreeMap((SortedMap) referenceObject)
            } else if (referenceObject instanceof SortedSet) {
                referenceObject = new TreeSet((SortedSet) referenceObject)
            } else if (referenceObject instanceof Set) {
                referenceObject = new HashSet((Set) referenceObject)
            } else if (referenceObject instanceof Map) {
                referenceObject = new HashMap((Map) referenceObject)
            } else if (referenceObject instanceof Collection) {
                referenceObject = new ArrayList((Collection) referenceObject)
            }
            xml.convertAnother(referenceObject)
        }
    }


    protected void asShortObject(
            Object refObj, XML xml, PersistentProperty idProperty,
            PersistentEntity referencedDomainClass
    ) throws ConverterException {

        MarshallingConfig refClassConfig = configPool.get(referencedDomainClass.getClass(), true)

        if (refClassConfig?.identifier) {
            if (refClassConfig.identifier.size() == 1 && refClassConfig.identifier[0] instanceof Closure) {
                refClassConfig.identifier[0].call(refObj, xml)
            } else {
                def wrapper = new BeanWrapperImpl(refObj)

                refClassConfig.identifier.each {
                    def val = wrapper.getPropertyValue(it)
                    xml.attribute(it, String.valueOf(val))
                }
            }

        } else {
            Object idValue

            if (proxyHandler instanceof EntityProxyHandler) {
                idValue = ((EntityProxyHandler) proxyHandler).getProxyIdentifier(refObj)
                if (idValue == null) {
                    idValue = new BeanWrapperImpl(refObj).getPropertyValue(idProperty.getName())
                }
            } else {
                idValue = new BeanWrapperImpl(refObj).getPropertyValue(idProperty.getName())
            }

            xml.attribute("id", String.valueOf(idValue))
        }
    }


    public static registerAttributeEditor(Class attrType, Class editorType) {
        attributeEditors.put(attrType, editorType)
    }

    @Override
    public String getElementName(Object value) {
        if (log.debugEnabled) {
            log.debug("Fetching element name for $value")
        }

        Class clazz = proxyHandler.unwrapIfProxy(value).getClass()

        //GrailsDomainClass domainClass = application.getArtefact(DomainClassArtefactHandler.TYPE, ConverterUtil.trimProxySuffix(clazz.getName()))
        PersistentEntity persistentEntity = application.mappingContext.getPersistentEntity(clazz.getName())

        MarshallingConfig marshallingConfig = configPool.get(clazz, true)

        return marshallingConfig.elementName ?: persistentEntity.getDecapitalizedName()
    }

    /**
     *
     * @return marshalling context for the xml marshalling
     */
    public static MarshallingContext getMarshallingContext() {
        return marshallingContext
    }
}

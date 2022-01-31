package org.grails.plugins.marshallers

import grails.converters.JSON
import groovy.util.logging.Slf4j
import org.grails.core.artefact.DomainClassArtefactHandler
import grails.core.GrailsApplication
import grails.util.GrailsClassUtils as GCU
//import grails.core.GrailsDomainClass
//import grails.core.GrailsDomainClassProperty
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
import org.grails.web.converters.marshaller.ObjectMarshaller
import org.grails.web.json.JSONWriter
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
class GenericDomainClassJSONMarshaller implements ObjectMarshaller<JSON> {
    private GrailsApplication application
    private ProxyHandler proxyHandler
    private MarshallingConfigPool configPool
    private static MarshallingContext marshallingContext = new MarshallingContext();

    public GenericDomainClassJSONMarshaller(ProxyHandler proxyHandler, GrailsApplication application, MarshallingConfigPool configPool) {
        if (log.debugEnabled) {
            log.debug("Registered json domain class marshaller")
        }

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
    public void marshalObject(Object v, JSON json) throws ConverterException {
        JSONWriter writer = json.getWriter()
        def value = proxyHandler.unwrapIfProxy(v)
        Class clazz = value.getClass()

        MarshallingConfig marshallingConfig = configPool.get(clazz, true)

/*        GrailsDomainClass domainClass = (GrailsDomainClass) application.getArtefact(
                DomainClassArtefactHandler.TYPE, ConverterUtil.trimProxySuffix(clazz.getName()))*/

        PersistentEntity persistentEntity = application.mappingContext.getPersistentEntity(clazz.getName())

        BeanWrapper beanWrapper = new BeanWrapperImpl(value)

        writer.object()

        if (marshallingConfig.shouldOutputClass) {
            writer.key("class").value(clazz.getName())
        }

        if (marshallingConfig.shouldOutputIdentifier) {
            //GrailsDomainClassProperty id = domainClass.getIdentifier()
            PersistentProperty id = persistentEntity.getIdentity()

            Object idValue = extractValue(value, id)
            json.property("id", idValue)
        }

        if (marshallingConfig.shouldOutputVersion) {
            //GrailsDomainClassProperty versionProperty = domainClass.getVersion()
            PersistentProperty versionProperty = persistentEntity.getVersion()

            Object version = extractValue(value, versionProperty)
            json.property("version", version)
        }

        //GrailsDomainClassProperty[] properties = domainClass.getPersistentProperties()
        List<PersistentProperty> properties = persistentEntity.getPersistentProperties()

        boolean includeMode = false

        if (marshallingConfig.include?.size() > 0) {
            includeMode = true
        }

        //for (GrailsDomainClassProperty property : properties) {
        for (PersistentProperty property : properties) {
            if (includeMode && marshallingConfig.include?.contains(property.getName())) {
                serializeProperty(property, marshallingConfig, beanWrapper, json, writer, value)
            } else if (!includeMode && !marshallingConfig.ignore?.contains(property.getName())) {
                serializeProperty(property, marshallingConfig, beanWrapper, json, writer, value)
            }
        }

        marshallingConfig.virtual?.each { prop, callable ->
            writer.key(prop)
            def cl = marshallingConfig.virtual[prop]

            if (cl.maximumNumberOfParameters == 2) {
                cl.call(value, json)
            } else {
                cl.call(value, json, marshallingContext)
            }

        }

        writer.endObject()
    }

    protected void serializeProperty(PersistentProperty property, MarshallingConfig marshallingConfig,
                                     BeanWrapper beanWrapper, JSON json, JSONWriter writer, def value) {

        writer.key(property.getName())

        if (marshallingConfig.serializer?.containsKey(property.getName())) {
            marshallingConfig.serializer[property.getName()].call(value, writer)
        } else {
            //if (!property.isAssociation()) {
            if (!(property instanceof Association)) {
                // Write non-relation property
                Object val = beanWrapper.getPropertyValue(property.getName())
                json.convertAnother(val)
            } else {
                Object referenceObject = beanWrapper.getPropertyValue(property.getName())

                if (marshallingConfig.deep?.contains(property.getName())) {
                    if (referenceObject == null) {
                        writer.value(null)
                    } else {
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

                        json.convertAnother(referenceObject)
                    }
                } else {
                    if (referenceObject == null) {
                        json.value(null)
                    } else {
                        //GrailsDomainClass referencedDomainClass = property.getReferencedDomainClass()
                        PersistentEntity referencedDomainClass = property.getAssociatedEntity()

                        // Embedded are now always fully rendered
                        if (referencedDomainClass == null || property.isEmbedded() || property.getType().isEnum()) {
                            json.convertAnother(referenceObject)
                        //} else if (property.isOneToOne() || property.isManyToOne() || property.isEmbedded()) {
                        } else if (property instanceof OneToOne || property instanceof ManyToOne || property instanceof Embedded) {
                            asShortObject(referenceObject, json, referencedDomainClass.getIdentity(), referencedDomainClass)
                        } else {
                            PersistentProperty referencedIdProperty = referencedDomainClass.getIdentity()

                            @SuppressWarnings("unused")
                            String refPropertyName = referencedDomainClass.getDecapitalizedName()

                            if (referenceObject instanceof Collection) {
                                Collection o = (Collection) referenceObject
                                writer.array()
                                for (Object el : o) {
                                    asShortObject(el, json, referencedIdProperty, referencedDomainClass)
                                }
                                writer.endArray()
                            } else if (referenceObject instanceof Map) {
                                Map<Object, Object> map = (Map<Object, Object>) referenceObject

                                for (Map.Entry<Object, Object> entry : map.entrySet()) {
                                    String key = String.valueOf(entry.getKey())
                                    Object o = entry.getValue()
                                    writer.object()
                                    writer.key(key)
                                    asShortObject(o, json, referencedIdProperty, referencedDomainClass)
                                    writer.endObject()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    //protected void asShortObject(Object refObj, JSON json, PersistentProperty idProperty, GrailsDomainClass referencedDomainClass) throws ConverterException {
    protected void asShortObject(
            Object refObj, JSON json, PersistentProperty idProperty,
            @SuppressWarnings("unused") PersistentEntity referencedDomainClass
    ) throws ConverterException {
        Object idValue

        if (proxyHandler instanceof EntityProxyHandler) {
            idValue = ((EntityProxyHandler) proxyHandler).getProxyIdentifier(refObj)
            if (idValue == null) {
                idValue = extractValue(refObj, idProperty)
            }
        } else {
            idValue = extractValue(refObj, idProperty)
        }

        JSONWriter writer = json.getWriter()
        writer.object()
        writer.key("id").value(idValue)
        writer.endObject()
    }

    //protected Object extractValue(Object domainObject, GrailsDomainClassProperty property) {
    protected Object extractValue(Object domainObject, PersistentProperty property) {
        BeanWrapper beanWrapper = new BeanWrapperImpl(domainObject)
        return beanWrapper.getPropertyValue(property.getName())
    }

    protected boolean isRenderDomainClassRelations() {
        return false
    }

    static MarshallingContext getMarshallingContext() {
        return marshallingContext
    }


}

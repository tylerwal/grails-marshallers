package org.grails.plugins.marshallers

import grails.plugins.*
import grails.util.GrailsUtil

class GrailsJsonMarshallerGrailsPlugin extends Plugin {

    def version = GrailsUtil.getGrailsVersion()

    def dependsOn = [converters: version]

    // the version or versions of Grails the plugin is designed for
    def grailsVersion = "5.1.1 > *"

    // resources that are excluded from plugin packaging
    def pluginExcludes = [
        "grails-app/views/error.gsp"
    ]

    def title = "Grails Json Marshaller" // Headline display name of the plugin
    def author = "Predrag Knezevic"
    def authorEmail = "pedjak@gmail.com"
    def description = '''\\
Easy registration and usage of custom XML and JSON marshallers supporting hierarchical configurations.

Further documentation can be found on the GitHub repo.
'''
    def profiles = []

    // URL to the plugin's documentation
    def documentation = "http://grails.org/plugin/grails-json-marshaller"

    // Extra (optional) plugin metadata

    // License: one of 'APACHE', 'GPL2', 'GPL3'
    def license = "APACHE"

    def artefacts = [
            XmlMarshallerArtefactHandler,
            JsonMarshallerArtefactHandler
    ]

    def watchedResources = [
            'file:./grails-app/domain/*.groovy',
            'file:./grails-app/conf/Config.groovy'
    ]


    // Online location of the plugin's browseable source code.
    def scm = [url: "https://github.com/pedjak/grails-marshallers"]

    Closure doWithSpring() {{->

            extendedConvertersConfigurationInitializer(ExtendedConvertersConfigurationInitializer)

            ["xml", "json"].each { type ->
                application."${type}MarshallerClasses".each { marshallerClass ->
                    "${marshallerClass.fullName}"(marshallerClass.clazz) { bean ->
                        bean.autowire = "byName"
                    }
                }
            }
    }}

    void doWithDynamicMethods() {
        applicationContext.extendedConvertersConfigurationInitializer.initialize()
        log.debug "Marshallers Plugin configured successfully"
    }

    void doWithApplicationContext() {
        // TODO Implement post initialization spring config (optional)
    }

    void onChange(Map<String, Object> event) {
        event.ctx.extendedConvertersConfigurationInitializer.initialize()
    }

    void onConfigChange(Map<String, Object> event) {
        // TODO Implement code that is executed when the project configuration changes.
        // The event is the same as for 'onChange'.
    }

    void onShutdown(Map<String, Object> event) {
        // TODO Implement code that is executed when the application shuts down (optional)
    }
}

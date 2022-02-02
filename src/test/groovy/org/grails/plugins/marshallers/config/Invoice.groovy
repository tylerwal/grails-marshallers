package org.grails.plugins.marshallers.config

import grails.persistence.Entity

@Entity
class Invoice {
	private static _m={}
	String name
	boolean admin
	Date created


	static hasMany = [items: Item]

	static def getMarshalling(){
		return _m
	}

	static def setMarshalling(value){
		_m=value
	}
}
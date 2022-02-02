package org.grails.plugins.marshallers.config

import grails.persistence.Entity

@Entity
class Item {
	static _m={}


	float amount
	String name

	static def getMarshalling(){
		return _m
	}

	static def setMarshalling(value){
		_m=value
	}
}
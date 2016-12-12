/*
 * Copyright (c) 2016, CauseCode Technologies Pvt Ltd, India.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or
 * without modification, are not permitted.
 */
package fileuploader

import grails.boot.GrailsApp
import grails.boot.config.GrailsAutoConfiguration

/**
 * The Application class used By Spring Boot to start the application.
 */
class Application extends GrailsAutoConfiguration {
    static void main(String[] args) {
        GrailsApp.run(Application, args)
    }
}

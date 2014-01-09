package com.lucastex.grails.fileuploader

import grails.util.Environment
import groovy.io.FileType

import java.nio.channels.FileChannel

import org.springframework.transaction.annotation.Transactional

import com.lucastex.grails.fileuploader.cdn.BlobDetail
import com.lucastex.grails.fileuploader.cdn.amazon.AmazonCDNFileUploaderImpl

class FileUploaderService {

    static transactional = false

    def CDNFileUploaderService
    def grailsApplication
    def messageSource
    def springSecurityService

    @Transactional
    UFile saveFile(String group, def file, String name = "", Locale locale = null) throws FileUploaderServiceException {
        Long fileSize
        boolean empty = true
        CDNProvider cdnProvider
        UFileType type = UFileType.LOCAL
        String contentType, fileExtension, fileName, path

        if(file instanceof File) {
            contentType = ""
            empty = !file.exists()
            fileName = file.name
            fileSize = file.size()
        } else {    // Means instance of Spring's CommonsMultipartFile.
            contentType = file?.contentType
            empty = file?.isEmpty()
            fileName = file?.originalFilename
            fileSize = file?.size
        }
        log.info "Received ${empty ? 'empty ' : ''}file [$fileName] of size [$fileSize] & content type [$contentType]."
        if(empty || !file) {
            return null
        }

        def config = grailsApplication.config.fileuploader[group]
        if(config.isEmpty()) {
            throw new FileUploaderServiceException("No config defined for group [$group]. Please define one in your Config file.")
        }
        int extensionAt = fileName.lastIndexOf('.') + 1
        if(extensionAt >= 0) {
            fileExtension = fileName.substring(extensionAt).toLowerCase()
        }

        if (!config.allowedExtensions[0].equals("*") && !config.allowedExtensions.contains(fileExtension)) {
            def msg = messageSource.getMessage("fileupload.upload.unauthorizedExtension",
                    [fileExtension, config.allowedExtensions] as Object[], locale)
            log.debug msg
            throw new FileUploaderServiceException(msg)
        }

        /**
         * If maxSize config exists
         */
        if (config.maxSize) {
            def maxSizeInKb = ((int) (config.maxSize)) / 1024
            if (fileSize > config.maxSize) { //if filesize is bigger than allowed
                log.debug "FileUploader plugin received a file bigger than allowed. Max file size is ${maxSizeInKb} kb"
                def msg = messageSource.getMessage("fileupload.upload.fileBiggerThanAllowed", [maxSizeInKb] as Object[], locale)
                throw new FileUploaderServiceException(msg)
            }
        }

        fileName = name ? (name + "." + fileExtension) : fileName
        fileName = fileName.trim().replaceAll(" ", "-")

        // Setup storage path
        def storageTypes = config.storageTypes

        if(storageTypes == "CDN") {
            type = UFileType.CDN_PUBLIC
            String containerName = config.container
            String userId = springSecurityService.currentUser?.id
            String tempFilePath = "./web-app/temp/${System.currentTimeMillis()}-${fileName}"
            if(config.provider == CDNProvider.AMAZON) {
                fileName = group + "-" + userId + "-" + System.currentTimeMillis() + "-" + fileName
            } else {
                fileName = group + "/" + userId + "/" + System.currentTimeMillis() + "/" + fileName
            }

            if(file instanceof File)
                file.renameTo(new File(tempFilePath))
            else
                file.transferTo(new File(tempFilePath))
            File tempFile = new File(tempFilePath)
            tempFile.deleteOnExit()
            if(Environment.current != Environment.PRODUCTION) {
                containerName += "-" + Environment.current.name
            }

            if(config.provider == CDNProvider.AMAZON) {
                cdnProvider = CDNProvider.AMAZON
                AmazonCDNFileUploaderImpl amazonFileUploaderInstance = getAmazonFileUploaderInstance()
                amazonFileUploaderInstance.authenticate()
                amazonFileUploaderInstance.uploadFile(containerName, tempFile, fileName, true)
                amazonFileUploaderInstance.close()
                path = amazonFileUploaderInstance.getURI(containerName, fileName)
            } else {
                cdnProvider = CDNProvider.RACKSPACE
                String publicBaseURL = CDNFileUploaderService.uploadFileToCDN(containerName, tempFile, fileName)
                path = publicBaseURL + "/" + fileName
            }
        } else {
            // Base path to save file
            path = config.path
            if(!path.endsWith('/')) path = path + "/";

            if(storageTypes?.contains('monthSubdirs')) {  //subdirectories by month and year
                Calendar cal = Calendar.getInstance()
                path = path + cal[Calendar.YEAR].toString() + cal[Calendar.MONTH].toString() + '/'
            } else {  //subdirectories by millisecond
                long currentTime = System.currentTimeMillis()
                path = path + currentTime + "/"
            }

            // Make sure the directory exists
            if(! new File(path).exists() ){
                if (!new File(path).mkdirs()) {
                    log.error "FileUploader plugin couldn't create directories: [${path}]"
                }
            }

            // If using the uuid storage type
            if(storageTypes?.contains('uuid')){
                path = path + UUID.randomUUID().toString()
            } else {  //note:  this type of storage is a bit of a security / data loss risk.
                path = path + fileName
            }

            // Move file
            log.debug "Moving [$fileName] to [${path}]."
            if(file instanceof File)
                file.renameTo(new File(path))
            else
                file.transferTo(new File(path))
        }

        UFile ufile = new UFile()
        ufile.name = fileName
        ufile.size = fileSize
        ufile.extension = fileExtension
        ufile.path = path
        ufile.type = type
        ufile.fileGroup = group
        ufile.provider = cdnProvider
        ufile.save()
        if(ufile.hasErrors()) {
            log.warn "Error saving UFile instance: $ufile.errors"
        }
        return ufile
    }

    @Transactional
    boolean deleteFile(Serializable idUfile) {
        UFile ufile = UFile.get(idUfile)
        if (!ufile) {
            log.error "No UFile found with id: [$idUfile]"
            return false
        }
        File file = new File(ufile.path)

        try {
            ufile.delete()
        } catch(Exception e) {
            log.error "Could not delete ufile: ${idUfile}", e
            return false
        }

        return true
    }

    boolean deleteFileForUFile(UFile ufileInstance) {
        if(ufileInstance.type in [UFileType.CDN_PRIVATE, UFileType.CDN_PUBLIC]) {
            if(ufileInstance.provider == CDNProvider.AMAZON) {
                AmazonCDNFileUploaderImpl amazonFileUploaderInstance = getAmazonFileUploaderInstance()
                amazonFileUploaderInstance.authenticate()
                amazonFileUploaderInstance.deleteFile(ufileInstance.container, ufileInstance.name)
                amazonFileUploaderInstance.close()
            } else {
                CDNFileUploaderService.deleteFile(ufileInstance.container, ufileInstance.name)
            }
            return true
        }
        File file = new File(ufileInstance.path)
        if(!file.exists()) {
            log.warn "No file found at path [$ufileInstance.path] for ufile [$ufileInstance.id]."
            return false
        }
        File timestampFolder = file.parentFile

        if (file.delete()) {
            log.debug "File [${file?.path}] deleted."

            int numFilesInParentFolder = 0
            timestampFolder.eachFile(FileType.FILES) {
                numFilesInParentFolder ++
            }
            if(numFilesInParentFolder == 0) {
                timestampFolder.delete()
            } else {
                log.debug "Not deleting ${timestampFolder} as it contains files"
            }
        } else {
            log.error "Could not delete file: ${file}"
        }
    }

    /**
     * Access the Ufile, returning the appropriate message if the UFile does not exist.
     */
    UFile ufileById(Serializable idUfile, Locale locale){
        UFile ufile = UFile.get(idUfile)

        if(ufile) {
            return ufile
        }
        String msg = messageSource.getMessage("fileupload.download.nofile", [idUfile] as Object[], locale)
        throw new FileNotFoundException(msg)
    }

    /**
     * Access the file held by the UFile, incrementing the viewed number, and returning appropriate message if file does not exist.
     */
    File fileForUFile(UFile ufile, Locale locale){
        File file = new File(ufile.path)

        if(file.exists()){
            //increment the viewed number
            ufile.downloads ++
            ufile.save()
            return file
        }
        String msg = messageSource.getMessage("fileupload.download.filenotfound", [ufile.name] as Object[], locale)
        throw new IOException(msg)
    }

    /**
     * Method to create a duplicate of an existing UFile
     * @param group
     * @param ufileInstance
     * @param name
     * @param locale
     * @throws FileUploaderServiceException
     * @throws IOException
     */
    @Transactional
    UFile cloneFile(String group, UFile ufileInstance, String name = "", Locale locale = null) throws FileUploaderServiceException, IOException {
        log.info "Cloning ufile [${ufileInstance?.id}][${ufileInstance?.name}]"
        if(!ufileInstance) {
            log.warn "Invalid/null ufileInstance received."
            return null
        }
        //Create temp directory
        def tempDirectory = "./web-app/temp/${System.currentTimeMillis()}/"
        new File(tempDirectory).mkdirs()

        //create file
        def tempFile = "${tempDirectory}/${ufileInstance.name}" // No need to append extension. name field already have that.
        def destFile = new File(tempFile)
        def sourceFile = new File(ufileInstance.path)
        if(!destFile.exists()) {
            destFile.createNewFile()
        }

        FileChannel source = null
        FileChannel destination = null

        try {
            source = new FileInputStream(sourceFile).getChannel()
            destination = new FileOutputStream(destFile).getChannel()
            destination.transferFrom(source, 0, source.size())
        } finally {
            source?.close()
            destination?.close()

            if(destFile.exists()) {
                return this.saveFile(group, destFile, name, locale)
            }
        }
    }

    String resolvePath(UFile ufileInstance) {
        if(!ufileInstance) {
            log.error "No Ufile instance found to resolve path."
            return ""
        }
        if(ufileInstance.type == UFileType.LOCAL) {
            return "/fileUploader/show/$ufileInstance.id"
        } else if(ufileInstance.type == UFileType.CDN_PUBLIC) {
            return ufileInstance.path
        }
    }

    List<Long> moveFileToCloud(List<UFile> ufileInstanceList, String containerName) {
        List<Long> failedUFileIdList = []
        List<BlobDetail> blobDetailList = []

        ufileInstanceList.each {
            String newFileName = "${System.currentTimeMillis()}-${it.name}"
            blobDetailList << new BlobDetail(newFileName, new File(it.path), it.id)
        }
        if(Environment.current != Environment.PRODUCTION) {
            containerName += "-" + Environment.current.name
        }

        CDNFileUploaderService.uploadFilesToCloud(containerName, blobDetailList)
        String baseURL = CDNFileUploaderService.cdnEnableContainer(containerName)

        blobDetailList.each {
            UFile uploadUFileInstance = ufileInstanceList.find { ufileInstance ->
                it.ufileId == ufileInstance.id
            }
            if(uploadUFileInstance) {
                if(it.eTag) {
                    uploadUFileInstance.name = it.remoteBlobName
                    uploadUFileInstance.path = baseURL + "/" + it.remoteBlobName
                    uploadUFileInstance.type = UFileType.CDN_PUBLIC
                    uploadUFileInstance.save()
                } else {
                    failedUFileIdList << it.ufileId
                }
            } else {
                log.error "Missing blobInstance. Never reach condition occured."
            }
        }
        return failedUFileIdList
    }

}
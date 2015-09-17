package com.lucastex.grails.fileuploader.cdn.amazon

import com.lucastex.grails.fileuploader.cdn.CDNFileUploader
import grails.util.Holders
import org.apache.commons.logging.Log
import org.apache.commons.logging.LogFactory
import org.jclouds.ContextBuilder
import org.jclouds.aws.s3.AWSS3Client
import org.jclouds.aws.s3.blobstore.options.AWSS3PutObjectOptions
import org.jclouds.blobstore.BlobStoreContext
import org.jclouds.http.HttpRequest
import org.jclouds.s3.domain.AccessControlList
import org.jclouds.s3.domain.AccessControlList.Permission
import org.jclouds.s3.domain.CannedAccessPolicy
import org.jclouds.s3.domain.S3Object
import org.jclouds.s3.domain.internal.MutableObjectMetadataImpl
import org.jclouds.s3.domain.internal.S3ObjectImpl
import org.jclouds.s3.options.CopyObjectOptions

class AmazonCDNFileUploaderImpl extends CDNFileUploader {

    private static Log log = LogFactory.getLog(this)

    AWSS3Client client

    AmazonCDNFileUploaderImpl(String accessKey, String accessSecret) {
        this.accessKey = accessKey
        this.accessSecret = accessSecret
    }

    static AmazonCDNFileUploaderImpl getInstance() {
        String key = Holders.getFlatConfig()["fileuploader.AmazonKey"]
        String secret = Holders.getFlatConfig()["fileuploader.AmazonSecret"]

        if (!key || !secret) {
            log.warn "No username or key configured for Amazon CDN service"
        }

        return new AmazonCDNFileUploaderImpl(key, secret)
    }

    @Override
    boolean authenticate() {
        context = ContextBuilder.newBuilder("aws-s3")
                .credentials(accessKey, accessSecret)
                .buildView(BlobStoreContext.class)
        println "Context created ${context.class}"

        blobStore = context.getBlobStore()
        println "Blobstore ${blobStore.class}"

        // Storing wrapped api of S3Client with apache jcloud
        client = context.unwrap().getApi()

        return false
    }

    @Override
    void close() {
        context.close()
    }

    @Override
    boolean containerExists(String name) {
        client.bucketExists(name)
    }

    @Override
    boolean createContainer(String name) {
        blobStore.createContainerInLocation(null, name)
    }

    @Override
    void deleteFile(String containerName, String fileName) {
        blobStore.removeBlob(containerName, fileName)
    }

    S3ObjectImpl getObject(String containerName, String fileName) {
        client.getObject(containerName, fileName, null)
    }

    /**
     * @param containerName Name of the bucket
     * @param fileName Name of the object in bucket
     * @param expiration expiration time in seconds for pre-signed URl.
     *        For example: 60 * 60 // For 1 hour.
     *
     * @see http://docs.aws.amazon.com/AmazonS3/latest/dev/ShareObjectPreSignedURLJavaSDK.html
     */
    @Override
    String getTemporaryURL(String containerName, String fileName, long expiration) {
        HttpRequest request = context.signer.signGetBlob(containerName, fileName, expiration)
        request.endpoint.toString()
    }

    //@Override
    String getPermanentURL(String containerName, String fileName) {
        getObject(containerName, fileName).metadata.uri
    }

    @Override
    boolean makeFilePublic(String containerName, String fileName) {
        AccessControlList acl = new AccessControlList()
        acl.addPermission(new URI("http://acs.amazonaws.com/groups/global/AllUsers"), Permission.READ)
        getObject(containerName, fileName).setAccessControlList(acl)
    }

    @Override
    boolean uploadFile(String containerName, File file, String fileName, boolean makePublic, long maxAge) {

        CannedAccessPolicy cannedAccessPolicy = makePublic ? CannedAccessPolicy.PUBLIC_READ : CannedAccessPolicy.PRIVATE

        AWSS3PutObjectOptions fileOptions = new AWSS3PutObjectOptions()
        fileOptions.withAcl(cannedAccessPolicy)

        MutableObjectMetadataImpl mutableObjectMetadata

        mutableObjectMetadata = new MutableObjectMetadataImpl()
        mutableObjectMetadata.setKey(fileName)

        mutableObjectMetadata.setCacheControl("max-age=$maxAge, public, must-revalidate, proxy-revalidate")
        log.info("Setting cache control in $fileName with max age $maxAge")

        S3Object s3ObjectToUpdate = new S3ObjectImpl(mutableObjectMetadata)

        if (s3ObjectToUpdate) {
            s3ObjectToUpdate.setPayload(file)
            client.putObject(containerName, s3ObjectToUpdate, fileOptions)
        }
        return true
    }

    /**
     * This method is used to update meta data of previously uploaded file. Amazon doesn't allow to update the metadata
     * of already existing file. Hence, updating the metadata by copying the file with new metadata with cache control.
     *
     * @param containerName String the name of the bucket
     * @param fileName String the name of the file to update metadata
     * @param maxAge long cache header's max age in seconds
     * @since 2.4.3
     * @author Priyanshu Chauhan
     */
    def updatePreviousFileMetaData(String containerName, String fileName, boolean isPublicACL, long maxAge) {
        Map metaData = [:]
        // GString doesn't work directly in metadata, hence creating a string type
        String cacheControl = "max-age=$maxAge, public, must-revalidate, proxy-revalidate"
        metaData["Cache-Control"] = cacheControl
        metaData["Content-Type"] = "application/unknown"

        CopyObjectOptions copyObjectOptions = new CopyObjectOptions()
        copyObjectOptions.overrideMetadataWith(metaData)

        CannedAccessPolicy cannedAccessPolicy = isPublicACL ? CannedAccessPolicy.PUBLIC_READ : CannedAccessPolicy.PRIVATE
        copyObjectOptions.overrideAcl(cannedAccessPolicy)

        client.copyObject(containerName, fileName, containerName, fileName, copyObjectOptions)
        return
    }
}
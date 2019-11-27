/*
 * Copyright © 2017 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package io.cdap.plugin.batchsink;

import com.google.auth.Credentials;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.WriteChannel;
import com.google.cloud.storage.*;
import com.voltage.securedata.enterprise.FPE;
import com.voltage.securedata.enterprise.LibraryContext;
import com.voltage.securedata.enterprise.VeException;
import io.cdap.plugin.batchsink.common.FieldInfo;
import io.cdap.plugin.batchsink.common.FileListData;
import io.cdap.plugin.batchsink.utils.FileMetaData;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.mapreduce.RecordWriter;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The record writer that takes file metadata and streams data from source database
 * to destination database
 */
public class FileAnonymizedRecordWriter extends RecordWriter<NullWritable, FileListData> {
    private static final Logger LOG = LoggerFactory.getLogger(FileAnonymizedRecordWriter.class);

    static Configuration conf = null;

    static {
        conf = new Configuration();
        conf.set("fs.hdfs.impl", org.apache.hadoop.hdfs.DistributedFileSystem.class.getName());
        conf.set("fs.file.impl", org.apache.hadoop.fs.LocalFileSystem.class.getName());
    }

    private final String bucketName;
    private final String project;
    private final String destinationPath;
    private final String suffix;
    private final String gcsServiceAccountJson;
    private Integer bufferSize;
    private Storage storage = null;
    private Bucket bucket = null;
    private String policyUrl;
    private String identity;
    private String sharedSecret;
    private String trustStorePath;
    private String cachePath;
    private String format;
    private String fieldList;
    private List<FieldInfo> fields;

    private LibraryContext library = null;
    private Map<String, FPE> mapFPE = new HashMap<>();

    /**
     * Construct a RecordWriter given user configurations.
     *
     * @param conf The configuration that contains required information to initialize the recordWriter.
     * @throws IOException
     */
    public FileAnonymizedRecordWriter(Configuration conf) throws IOException {
        LOG.info("Initializing of RecordWriter");

        bucketName = conf.get(FileAnonymizedOutputFormat.NAME_GCS_BUCKET, null);
        LOG.info("Bucket Name - " + bucketName);

        project = conf.get(FileAnonymizedOutputFormat.NAME_GCS_PROJECTID, null);
        LOG.info("GCS Project ID - " + project);

        gcsServiceAccountJson = conf.get(FileAnonymizedOutputFormat.NAME_GCS_SERVICEACCOUNTJSON, null);
        LOG.info("GCS Service Account-" + gcsServiceAccountJson);

        destinationPath = conf.get(FileAnonymizedOutputFormat.NAME_GCS_DESTPATH, null);
        LOG.info("Dest Path - " + destinationPath);

        suffix = conf.get(FileAnonymizedOutputFormat.NAME_GCS_DESTPATH_SUFFIX, null);
        LOG.info("Suffix - " + suffix);

        String size = conf.get(FileAnonymizedOutputFormat.NAME_BUFFER_SIZE, null);
        LOG.info("Buffer size - " + size);
        bufferSize = StringUtils.isNumeric(size) ? Integer.parseInt(size) : 1024;
        if (bufferSize <= 0) {
            bufferSize = 1024;
        }
        LOG.info("Buffer size applied - " + bufferSize);

        format = conf.get(FileAnonymizedOutputFormat.NAME_FILE_FORMAT, null);
        LOG.info("Format - " + format);

        fieldList = conf.get(FileAnonymizedOutputFormat.NAME_FIELD_LIST, null);
        LOG.info("Field List - " + fieldList);

        parseFields(fieldList);

        // Create GCS Storage using the credentials
        storage = getGoogleStorage(gcsServiceAccountJson, project);
        LOG.info("Created GCS Storage");
        bucket = getBucket(storage, bucketName);
        LOG.info("Created GCS Bucket");

        try {
            LOG.info("In initialize");
            System.setProperty("java.library.path", "/opt/DA/javasimpleapi/lib");
            /*
            LOG.info("Print all default System Properties as seen by Plugin");
            Properties p = System.getProperties();
            Enumeration keys = p.keys();
            while (keys.hasMoreElements()) {
                String key = (String) keys.nextElement();
                String value = (String) p.get(key);
                LOG.info(key + " : " + value);
            }
            */

            LOG.info("java.library.path = {}", "/opt/DA/javasimpleapi/lib");
            Field fieldSysPath = ClassLoader.class.getDeclaredField("sys_paths");
            fieldSysPath.setAccessible(true);
            fieldSysPath.set(null, null);

            // Load the JNI library
            System.loadLibrary("vibesimplejava");

            // Print the API version
            LOG.info("SimpleAPI version: " + LibraryContext.getVersion());

            // Sample configuration
            /*
            https://voltage-pp-0000.dataprotection.voltage.com/policy/clientPolicy.xml
            accounts22@dataprotection.voltage.com
            voltage123

            /opt/DA/javasimpleapi/trustStore
            /opt/DA/javasimpleapi/cache

            /opt/cdap-test-data/csv/

            midyear-courage-256620

            gs://shiva-cirus-test/disttest/

            /opt/cdap-test-data/midyear-courage-256620-0b0bc77ed7d8.json
            */

            policyUrl = conf.get(FileAnonymizedOutputFormat.NAME_POLICY_URL, null);
            LOG.info("Policy URL - " + policyUrl);

            identity = conf.get(FileAnonymizedOutputFormat.NAME_IDENTITY, null);
            LOG.info("Identity - " + identity);

            sharedSecret = conf.get(FileAnonymizedOutputFormat.NAME_SHARED_SECRET, null);
            LOG.info("Shared Secret - " + sharedSecret);

            trustStorePath = conf.get(FileAnonymizedOutputFormat.NAME_TRUST_STORE_PATH, null);
            LOG.info("Trust Store Path - " + trustStorePath);

            cachePath = conf.get(FileAnonymizedOutputFormat.NAME_CACHE_PATH, null);
            LOG.info("Cache Path - " + cachePath);

            LOG.info("Building LibraryContext...");

            // Create the context for crypto operations
            library = new LibraryContext.Builder()
                    .setPolicyURL(policyUrl)
                    .setFileCachePath(cachePath)
                    .setTrustStorePath(trustStorePath)
                    .build();

            /*
            // Dev Guide Code Snippet: FPEBUILD; LC:5
            // Protect and access the credit card number
            fpe = library.getFPEBuilder(format)
                    .setSharedSecret(sharedSecret)
                    .setIdentity(identity)
                    .build();
            */

            //Build map of FPE objects for each format
            for (FieldInfo field : fields) {
                if (!field.isAnonymize()) {
                    continue;
                }
                String fieldFormat = field.getFormat();
                LOG.info("Building library.getFPEBuilder for {}...", fieldFormat);
                FPE fpe = mapFPE.get(fieldFormat);
                if (fpe == null) {
                    // Dev Guide Code Snippet: FPEBUILD; LC:5
                    // Protect and access the credit card number
                    fpe = library.getFPEBuilder(fieldFormat)
                            .setSharedSecret(sharedSecret)
                            .setIdentity(identity)
                            .build();
                    mapFPE.put(fieldFormat, fpe);
                }
            }
        } catch (VeException | NoSuchFieldException | IllegalAccessException ve) {
            LOG.error("Error loading library", ve);
            throw new IOException(ve);
        }
    }

    private void parseFields(String fieldList) {
        fields = new ArrayList<>();

        String[] list = StringUtils.splitPreserveAllTokens(fieldList, ",");
        for (String entry : list) {
            String[] attributes = StringUtils.splitPreserveAllTokens(entry, ":", 3);
            FieldInfo field = new FieldInfo(attributes[0], attributes[1].equalsIgnoreCase("Yes"), attributes[2]);
            fields.add(field);
        }

        LOG.info("fields = {}", StringUtils.join(fields, "#"));
    }

    private static FileMetaData getFileMetaData(String filePath, String uri) throws IOException {
        return new FileMetaData(uri + '/' + filePath, conf);
    }

    private Storage getGoogleStorage(String serviceAccountJSON, String project) {
        Credentials credentials = null;
        try {
            credentials = GoogleCredentials.fromStream(new FileInputStream(serviceAccountJSON));
        } catch (IOException e) {
            LOG.error(e.getMessage(), e);
        }

        Storage storage = StorageOptions.newBuilder()
                .setCredentials(credentials)
                .setProjectId(project)
                .build()
                .getService();

        return storage;
    }

    private Bucket getBucket(Storage storage, String bucketName) {
        Bucket bucket = storage.get(bucketName);
        if (bucket == null) {
            LOG.info("Creating new bucket '{}'.", bucketName);
            bucket = storage.create(BucketInfo.of(bucketName));
        }
        return bucket;
    }

    /**
     * This method connects to the source filesystem and copies the file specified by the FileMetadata input to the
     * destination filesystem.
     *
     * @param key          Unused key.
     * @param fileListData Contains metadata for the file we wish to copy.
     * @throws IOException
     * @throws InterruptedException
     */
    @Override
    public void write(NullWritable key, FileListData fileListData) throws IOException, InterruptedException {
        LOG.info("In write");
        if (fileListData.getRelativePath().isEmpty()) {
            LOG.info("fileListData.getRelativePath() is empty");
            return;
        }

        // construct file paths for source and destination
        String outFileName = destinationPath + fileListData.getRelativePath();
        String contentType = "text/csv";

        FileMetaData fileMetaData = null;
        String fullPath = fileListData.getFullPath();
        if (fullPath != null) {
            try {
                fileMetaData = getFileMetaData(fullPath, fileListData.getHostURI());
            } catch (IOException e) {
                LOG.error(e.getMessage(), e);
            }
        }

        LOG.info("Output File Name " + outFileName);

        /*
        try {
            String plaintext = "123-45-6789";

            LOG.info("Anonymizing {}...", plaintext);
            // Dev Guide Code Snippet: FPEPROTECT; LC:1
            String ciphertext = fpe.protect(plaintext);

            LOG.info("Anonymizing {} = {}...", plaintext, ciphertext);
        } catch (VeException ve) {
            LOG.error("Error while anonymizing.", ve);
        }
        */

        BlobId blobId = BlobId.of(bucket.getName(), outFileName);
        BlobInfo blobInfo = BlobInfo.newBuilder(blobId).setContentType(contentType).build();

        InputStream inputStream = null;

        try {
            inputStream = getAnonymizedStream(fileMetaData);
        } catch (Exception e) {
            LOG.error(String.format("Failed while anonymizing the file '%s'", fileMetaData.getPath().getName()), e);
            inputStream = null;
            throw new IOException(String.format("Failed while anonymizing the file '%s'", fileMetaData.getPath().getName()));
        }

        if (inputStream == null) {
            LOG.warn("File Anonymization failed for {} hence skipping GCS upload.", fileMetaData.getPath().getName());
            return;
        }

        try {
            byte[] buffer = new byte[bufferSize];
            try (WriteChannel writer = storage.writer(blobInfo)) {
                int limit;
                while ((limit = inputStream.read(buffer)) >= 0) {
                    LOG.debug("upload file " + limit);
                    writer.write(ByteBuffer.wrap(buffer, 0, limit));
                }
            }
        } catch (IOException ie) {
            LOG.error(ie.getMessage(), ie);
        } finally {
            if (inputStream != null) {
                inputStream.close();
            }
        }

        LOG.info("write completed");
    }

    private void buildAnonymizedContents(OutputStream outPipe, FileMetaData fileMetaData) throws IOException {
        LOG.info("In buildAnonymizedContents");
        try (
                FSDataInputStream in = fileMetaData.getFileSystem().open(fileMetaData.getPath());
                InputStreamReader reader = new InputStreamReader(in);
                CSVParser csvParser = new CSVParser(reader, CSVFormat.DEFAULT);
        ) {
            List<CSVRecord> records = csvParser.getRecords();
            if (records.isEmpty()) {
                LOG.warn(String.format("The input file '%s' is empty", fileMetaData.getPath().getName()));
                throw new IOException(String.format("The input file '%s' is empty", fileMetaData.getPath().getName()));
            }

            LOG.info("# of records in {} = {}", fileMetaData.getPath().getName(), records.size());

            CSVPrinter printer = new CSVPrinter(new OutputStreamWriter(outPipe), CSVFormat.DEFAULT);

            List<Object> fieldValues = new ArrayList<>();
            for (CSVRecord csvRecord : records) {
                LOG.info("Processing record {}", csvRecord.getRecordNumber());
                if (csvRecord.size() < fields.size()) {
                    LOG.error(String.format("Invalid number of columns at line %d in '%s' file", csvRecord.getRecordNumber(), fileMetaData.getPath().getName()));
                    printer.close();
                    throw new IOException(String.format("Invalid number of columns at line %d in '%s' file", csvRecord.getRecordNumber(), fileMetaData.getPath().getName()));
                }

                fieldValues.clear();

                //Loop through all columns in the incoming file
                for (int columnIndex = 0; columnIndex < csvRecord.size(); columnIndex++) {
                    FieldInfo field = null;
                    if (columnIndex < fields.size()) {
                        field = fields.get(columnIndex);
                    }

                    String plainText = csvRecord.get(columnIndex);
                    LOG.info("R{}:C{} = {}", csvRecord.getRecordNumber(), columnIndex, plainText);

                    //TODO: add config for skipping first row
                    //Skip the header row
                    if (csvRecord.getRecordNumber() == 1) {
                        LOG.info("Header row");
                        fieldValues.add(plainText);
                    } else {
                        //if field is marked for anonymization then call api
                        if (field != null && field.isAnonymize() && StringUtils.isNotEmpty(plainText)) {
                            LOG.info("field != null && field.isAnonymize()");
                            String cipherText = anonymize(plainText, field.getFormat());
                            if (cipherText == null) {
                                LOG.error(String.format("Unable to anonymize '%s' value for column '%s' at line %d in '%s' file.",
                                        plainText, field.getName(), csvRecord.getRecordNumber(), fileMetaData.getPath().getName()));
                                printer.close();
                                throw new IOException(String.format("Unable to anonymize '%s' value for column '%s' at line %d in '%s' file.",
                                        plainText, field.getName(), csvRecord.getRecordNumber(), fileMetaData.getPath().getName()));
                            } else {
                                fieldValues.add(cipherText);
                            }
                        } else {
                            //otherwise use the original value as is
                            LOG.info("in else");
                            fieldValues.add(plainText);
                        }
                    }
                }
                printer.printRecord(fieldValues);
            }

            LOG.info("Processed all records");

            printer.close();
        } catch (Exception e) {
            throw new IOException(e);
        }
        LOG.info("buildAnonymizedContents completed");
    }

    private String anonymize(String plainText, String format) {
        String cipherText = null;
        try {
            LOG.info("Anonymizing {} with {} format...", plainText, format);

            FPE fpe = mapFPE.get(format);
            if (fpe == null) {
                LOG.error("Invalid Anonymize format {}", format);
                return null;
            }
            // Dev Guide Code Snippet: FPEPROTECT; LC:1
            cipherText = fpe.protect(plainText);

            LOG.info("Anonymizing {} with {} format = {}...", plainText, format, cipherText);
        } catch (VeException ve) {
            LOG.error("Error while anonymizing.", ve);
        }

        return cipherText;
    }

    private InputStream getAnonymizedStream(FileMetaData fileMetaData) throws IOException {
        LOG.info("In getAnonymizedStream");
        PipedOutputStream outPipe = new PipedOutputStream();
        PipedInputStream inPipe = new PipedInputStream();
        inPipe.connect(outPipe);

        new Thread(
                () -> {
                    try {
                        LOG.info("In getAnonymizedStream::thread");
                        buildAnonymizedContents(outPipe, fileMetaData);
                    } catch (IOException e) {
                        LOG.error("Failed in getAnonymizedStream::Thread", e);
                        throw new RuntimeException("Failed while getAnonymizedStream");
                    } finally {
                        try {
                            outPipe.close();
                        } catch (IOException e) {
                            LOG.error("Failed while closing outPipe in getAnonymizedStream::Thread", e);
                            throw new RuntimeException("Failed while closing outPipe in getAnonymizedStream");
                        }
                    }
                })
                .start();

        LOG.info("getAnonymizedStream completed");
        return inPipe;
    }

    @Override
    public void close(TaskAttemptContext taskAttemptContext) throws IOException, InterruptedException {
        //TODO: clean up fpe.delete() and library.delete()
    }
}

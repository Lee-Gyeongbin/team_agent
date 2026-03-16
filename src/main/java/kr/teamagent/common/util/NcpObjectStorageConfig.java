package kr.teamagent.common.util;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;

@Configuration
public class NcpObjectStorageConfig {

    @Bean
    public AmazonS3 amazonS3() {

        String endpoint = PropertyUtil.getProperty("ncp.storage.endpoint");
        String region = PropertyUtil.getProperty("ncp.storage.region");
        String accessKey = PropertyUtil.getProperty("ncp.storage.accessKey");
        String secretKey = PropertyUtil.getProperty("ncp.storage.secretKey");

        BasicAWSCredentials credentials =
                new BasicAWSCredentials(accessKey, secretKey);

        return AmazonS3ClientBuilder.standard()
                .withEndpointConfiguration(
                        new AwsClientBuilder.EndpointConfiguration(endpoint, region))
                .withCredentials(new AWSStaticCredentialsProvider(credentials))
                .build();
    }
}
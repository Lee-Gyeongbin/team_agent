package kr.teamagent.common.util;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;

/**
 * AWS S3 클라이언트 설정.
 *
 * <ul>
 *   <li>{@code aws.s3.accessKey}/{@code aws.s3.secretKey}가 둘 다 있으면 → 해당 키로 인증</li>
 *   <li>하나라도 비어 있으면 → DefaultAWSCredentialsProviderChain
 *       (서버는 EC2 인스턴스 IAM Role, 로컬은 ~/.aws/credentials 를 자동으로 탐색)</li>
 * </ul>
 */
@Configuration
public class S3StorageConfig {

    /** 리전이 지정되지 않았을 때 사용할 기본 AWS 리전. */
    private static final String DEFAULT_AWS_REGION = "ap-northeast-2";

    @Bean
    public AmazonS3 amazonS3() {

        String region    = blankIfNull(PropertyUtil.getProperty("aws.s3.region"));
        String accessKey = blankIfNull(PropertyUtil.getProperty("aws.s3.accessKey"));
        String secretKey = blankIfNull(PropertyUtil.getProperty("aws.s3.secretKey"));

        AmazonS3ClientBuilder builder = AmazonS3ClientBuilder.standard();

        builder.withRegion(region.isEmpty() ? DEFAULT_AWS_REGION : region);

        if (accessKey.isEmpty() || secretKey.isEmpty()) {
            // 액세스 키 미지정 → EC2 인스턴스 IAM Role 등 기본 자격증명 체인 사용
            builder.withCredentials(new DefaultAWSCredentialsProviderChain());
        } else {
            builder.withCredentials(
                    new AWSStaticCredentialsProvider(new BasicAWSCredentials(accessKey, secretKey)));
        }

        return builder.build();
    }

    private String blankIfNull(String value) {
        return value == null ? "" : value.trim();
    }
}

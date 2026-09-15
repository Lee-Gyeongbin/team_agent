package kr.teamagent.common.util;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;

/**
 * 오브젝트 스토리지(AmazonS3) 클라이언트 설정.
 *
 * <p>NCP Object Storage와 AWS S3를 모두 지원한다. 분기 기준은 프로퍼티 값이며 코드 수정 없이 전환된다.
 *
 * <ul>
 *   <li>{@code ncp.storage.endpoint} 값이 있으면 → 해당 엔드포인트 사용 (NCP Object Storage)
 *   <li>{@code ncp.storage.endpoint} 값이 비어 있으면 → {@code ncp.storage.region} 리전의 AWS S3 사용
 *   <li>{@code ncp.storage.accessKey}/{@code secretKey}가 있으면 → 해당 키로 인증
 *   <li>둘 다 비어 있으면 → DefaultAWSCredentialsProviderChain (EC2 인스턴스 IAM Role, 환경변수,
 *       ~/.aws/credentials 순으로 탐색)
 * </ul>
 *
 * <p>프로퍼티 키 이름({@code ncp.storage.*})은 이미 여러 서비스 구현체에서 참조하고 있어 현재는 그대로 둔다.
 * AWS 전환이 안정화된 뒤 {@code aws.s3.*}로 일괄 리네이밍할 것.
 */
@Configuration
public class NcpObjectStorageConfig {

    /** 엔드포인트를 지정하지 않았을 때 사용할 기본 AWS 리전. */
    private static final String DEFAULT_AWS_REGION = "ap-northeast-2";

    @Bean
    public AmazonS3 amazonS3() {

        String endpoint = blankIfNull(PropertyUtil.getProperty("ncp.storage.endpoint"));
        String region = blankIfNull(PropertyUtil.getProperty("ncp.storage.region"));
        String accessKey = blankIfNull(PropertyUtil.getProperty("ncp.storage.accessKey"));
        String secretKey = blankIfNull(PropertyUtil.getProperty("ncp.storage.secretKey"));

        AmazonS3ClientBuilder builder = AmazonS3ClientBuilder.standard();

        if (endpoint.isEmpty()) {
            // AWS S3 — 엔드포인트는 SDK가 리전으로부터 생성
            builder.withRegion(region.isEmpty() ? DEFAULT_AWS_REGION : region);
        } else {
            // NCP Object Storage 등 S3 호환 스토리지
            builder.withEndpointConfiguration(
                    new AwsClientBuilder.EndpointConfiguration(endpoint, region));
        }

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

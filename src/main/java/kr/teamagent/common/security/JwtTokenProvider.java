package kr.teamagent.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import kr.teamagent.common.security.service.UserVO;
import kr.teamagent.common.security.service.impl.LoginServiceImpl;
import kr.teamagent.common.util.PropertyUtil;
import kr.teamagent.common.util.SessionUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import java.nio.charset.Charset;
import java.util.*;

@Component
public class JwtTokenProvider {
    //private final static long TOKEN_VALID_MILISECOND = 1000L * 60 * 60 * 24; // 24시간

    private final static long TOKEN_VALID_MILISECOND = 1000L * 60 * 60 * 24 * Integer.MAX_VALUE; // 무한

    @Autowired
    LoginServiceImpl loginService;
    private static String secretKey;

    public JwtTokenProvider(/*@Qualifier("userUserDetailsService") UserDetailsService userDetailsService*/) {
        //this.userDetailsService = userDetailsService;
    }

    @PostConstruct
    protected void init() {
        secretKey = PropertyUtil.getProperty("Globals.hashedPassword");
        //secretKey = Base64.getEncoder().encodeToString(secretKey.getBytes());
    }

    // Jwt 토큰 생성
    public static String createToken(String userPk, HashMap<String, Object> claimMap) {
        return createToken(userPk, claimMap, TOKEN_VALID_MILISECOND); // Token 생성
    }
    // Jwt 토큰 생성
    public static String createToken(String userPk, HashMap<String, Object> claimMap, long tokenValidMiliSecond) {
        Claims claims = Jwts.claims().setSubject(userPk);
        if(claimMap!=null){
            for(String claim : claimMap.keySet())
            {
                claims.put(claim, claimMap.get(claim));
            }
        }
        Date now = new Date();
        return Jwts.builder()
                .setClaims(claims) // 데이터
                .setIssuedAt(now)   // 토큰 발행 일자
                .setExpiration(new Date(now.getTime() + tokenValidMiliSecond)) // 만료 기간
                .signWith(SignatureAlgorithm.HS512, secretKey) // 암호화 알고리즘, secret 값
                .compact(); // Token 생성
    }

    public static String createApiToken(String compId, String userId) {
        //admin@naver.com
        String email = String.format("%s@%s.com", userId, compId);

        Map<String, Object> headers = new HashMap<>();
        Map<String, Object> payloads = new HashMap<>();
        headers.put("typ", "JWT");
        headers.put("alg", "HS256");
        payloads.put("email", email);
        Date expiration = new Date();
        expiration.setTime(expiration.getTime() + TOKEN_VALID_MILISECOND);
        //System.out.println("signedWith : " + secretKey + "...");

        return Jwts.builder()
                .setHeader(headers)
                .setClaims(payloads) // 데이터
                .setSubject("user")
                .setExpiration(expiration) // 만료 기간
                .signWith(SignatureAlgorithm.HS256, secretKey) // 암호화 알고리즘, secret 값
                .compact(); // Token 생성

    }

    // Jwt Token에서 User PK 추출
    public static String getUserPk(String token) {
        return Jwts.parser().setSigningKey(secretKey)
                .parseClaimsJws(token).getBody().getSubject();
    }

    public static String getClaim(String token, String claim) {
        return (String)(Jwts.parser().setSigningKey(secretKey)
                .parseClaimsJws(token).getBody().get(claim));
    }
    public Claims getClaims(String token) {
        return Jwts.parser().setSigningKey(secretKey).parseClaimsJws(token).getBody();
    }

    public Optional<String> resolveToken(HttpServletRequest req) throws Exception {
        Optional<String> token = Optional.ofNullable(req.getHeader("X-AUTH-TOKEN"));
        if (!token.isPresent()) {
            token = Optional.ofNullable(req.getParameter("token"));
        }
        return token;
    }

    // Jwt Token의 유효성 및 만료 기간 검사
    public static boolean validateToken(String jwtToken) {
        try {
            Jws<Claims> claims = Jwts.parser().setSigningKey(secretKey).parseClaimsJws(jwtToken);
            return !claims.getBody().getExpiration().before(new Date());
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

}

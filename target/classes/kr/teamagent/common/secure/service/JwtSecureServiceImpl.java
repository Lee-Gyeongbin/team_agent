package kr.teamagent.common.secure.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import kr.teamagent.common.util.SessionUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class JwtSecureServiceImpl {

    private final int TIME_MILL = 2*60*1000;

    @Autowired
	private CryptoKeyHolder cryptoKeyHolder;

    public String generateToken(){

        String token = "";

        SignatureAlgorithm signatureAlgorithm = SignatureAlgorithm.HS512;
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        List<GrantedAuthority> authorities = authentication.getAuthorities().stream().collect(Collectors.toList());

        Map<String,Object> claims= new HashMap<String,Object>();
        claims.put("roles",authorities);
        claims.put("compId", SessionUtil.getCompId());
        claims.put("userId", SessionUtil.getUserId());

        Date now = new Date();

        token = Jwts.builder().setClaims(claims)
                .signWith(signatureAlgorithm,cryptoKeyHolder.getSecureKey())
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime()+TIME_MILL))
                .compact();

        //System.out.println("token : "+token);

        return token;
    }

}

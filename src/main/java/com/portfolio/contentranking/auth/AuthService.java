package com.portfolio.contentranking.auth;

import com.portfolio.contentranking.auth.dto.LoginRequest;
import com.portfolio.contentranking.auth.dto.SignupRequest;
import com.portfolio.contentranking.auth.dto.SignupResponse;
import com.portfolio.contentranking.auth.dto.TokenResponse;
import com.portfolio.contentranking.common.exception.DuplicateEmailException;
import com.portfolio.contentranking.common.exception.LoginFailedException;
import com.portfolio.contentranking.member.Member;
import com.portfolio.contentranking.member.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;

    @Transactional
    public SignupResponse signup(SignupRequest request) {
        if (memberRepository.existsByEmail(request.email())) {
            throw new DuplicateEmailException();
        }

        Member member = Member.builder()
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .nickname(request.nickname())
                .build();

        memberRepository.save(member);

        return SignupResponse.from(member);
    }

    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request) {
        Member member = memberRepository.findByEmail(request.email())
                .orElseThrow(LoginFailedException::new);

        if (!passwordEncoder.matches(request.password(), member.getPassword())) {
            throw new LoginFailedException();
        }

        String accessToken = jwtProvider.createToken(member.getId());
        return TokenResponse.of(accessToken, jwtProvider.getExpiresInSeconds());
    }
}
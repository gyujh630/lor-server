package com.leagueofrestaurant.web.review.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueofrestaurant.api.ocr.OcrService;
import com.leagueofrestaurant.web.common.CommonService;
import com.leagueofrestaurant.web.exception.ErrorCode;
import com.leagueofrestaurant.web.exception.LORException;
import com.leagueofrestaurant.web.member.domain.Member;
import com.leagueofrestaurant.web.review.domain.Review;
import com.leagueofrestaurant.web.review.dto.ReceiptInfo;
import com.leagueofrestaurant.web.review.dto.ReviewContent;
import com.leagueofrestaurant.web.review.repository.ReviewRepository;
import com.leagueofrestaurant.web.member.repository.MemberRepository;
import com.leagueofrestaurant.web.store.domain.Store;
import com.leagueofrestaurant.web.store.dto.RequestStoreDto;
import com.leagueofrestaurant.web.store.dto.ResponseStoreDto;
import com.leagueofrestaurant.web.store.dto.StoreSearchCondition;
import com.leagueofrestaurant.web.store.repository.StoreRepository;
import com.leagueofrestaurant.web.store.service.StoreService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ReviewService {
    private final ReviewRepository reviewRepository;
    private final MemberRepository memberRepository;
    private final StoreRepository storeRepository;
    private final CommonService commonService;
    private final StoreService storeService;
    private final OcrService ocrService;
    private final ObjectMapper objectMapper;

    // 모든 리뷰 조회
    public List<ReviewContent> getAllReviews() {
        List<Review> reviews = reviewRepository.findAll();

        return reviews.stream()
                .map(review -> new ReviewContent(review.getContent(), review.getRatingPoint(), review.getImg(), review.getSeason()))
                .collect(Collectors.toList());
    }

    // 특정 리뷰 조회
    public ReviewContent getReview(Long reviewId) {
        Optional<Review> optionalReview = reviewRepository.findById(reviewId);

        if (optionalReview.isPresent()) {
            Review review = optionalReview.get();
            return new ReviewContent(review.getContent(), review.getRatingPoint(), review.getImg(), review.getSeason());
        } else {
            throw new LORException(ErrorCode.NOT_EXIST_REVIEW);
        }
    }

    //특정 회원의 리뷰 조희
    public List<ReviewContent> getReviewsByMemberId(Long memberId) {
        List<Review> reviews = reviewRepository.findAllByMemberId(memberId);

        return reviews.stream()
                .map(review -> new ReviewContent(review.getContent(), review.getRatingPoint(), review.getImg(), review.getSeason()))
                .collect(Collectors.toList());
    }

    // 특정 가게의 리뷰 조회
    public List<ReviewContent> getReviewsByStoreId(Long storeId) {
        List<Review> reviews = reviewRepository.findAllByStoreId(storeId);

        return reviews.stream()
                .map(review -> new ReviewContent(review.getContent(), review.getRatingPoint(), review.getImg(), review.getSeason()))
                .collect(Collectors.toList());
    }

    //영수증 인증
    public ReceiptInfo getReceiptInfo(MultipartFile image) throws IOException {
        String response = ocrService.getReceiptJSON(image);
        JsonNode jsonNode = objectMapper.readTree(response);
        List<String> receiptData = ocrService.getReceiptInfo(jsonNode);

        String message = receiptData.get(0); //성공 여부

        if (!"SUCCESS".equals(message)) { //실패
            throw new LORException(ErrorCode.RECEIPT_ERROR);
        }
        else{ //성공
            String storeName = receiptData.get(1);
            String storeAddress = receiptData.get(2);
            String city = commonService.getCityString(storeAddress); // 서울,인천,경기도가 아닌 경우 예외 처리
            if(commonService.isBoundary(city)){
                return new ReceiptInfo(storeName, storeAddress);
            }
            else{ //서비스 지역이 아님
                throw new LORException(ErrorCode.NOT_SUPPORT_AREA);
            }
        }
    }

    //리뷰 생성
    @Transactional
    public void createReview(long memberId, ReviewContent reviewContent, ReceiptInfo receiptInfo) {
        // member가 존재하지 않는 경우 예외 처리
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new LORException(ErrorCode.NOT_EXIST_MEMBER));

        // 영수증 정보에서 가게명, 주소 추출
        String storeName = receiptInfo.getStoreName();
        String address = receiptInfo.getAddress();

        // 시즌 추출
        String season = commonService.getSeason();

        // 가게명과 주소로 store를 찾기
        StoreSearchCondition storeSearchCondition = new StoreSearchCondition(storeName, address);
        List<ResponseStoreDto> stores = storeService.getStoreListByCondition(storeSearchCondition);

        if (stores.isEmpty()) { // 존재하지 않을 경우 : 가게 생성
            RequestStoreDto requestStoreDto = new RequestStoreDto(storeName, address, null, null);
            try {
                storeService.createStore(requestStoreDto);
            } catch(IllegalArgumentException e) {
                throw new LORException(ErrorCode.FAIL_TO_CREATE_STORE);
            }
        }
        else { // 존재하는 경우 : 시즌 내 동일 가게에 리뷰 이력 있는지 확인
            List<Review> seasonReviews = reviewRepository.findByMemberIdAndStoreId(member.getId(), stores.get(0).getId(), season);
            if(!(seasonReviews.isEmpty())){
                throw new LORException(ErrorCode.DUPLICATE_REVIEW);
            }
        }

        List<Store> targetStores = storeRepository.findStoreListByCondition(storeSearchCondition);
        Store targetStore = targetStores.get(0);
        String content = reviewContent.getContent();
        String img = reviewContent.getImg();
        Integer ratingPoint = reviewContent.getRatingPoint();

        //리뷰 객체 생성 및 데이터베이스에 저장
        Review review = new Review(ratingPoint, content, season, img, member, targetStore);
        reviewRepository.save(review);

        //targetStore의 평균 별점을 업데이트.. 그리고 실시간 점수(score 계산)
    }

    // 리뷰 삭제
    @Transactional
    public void deleteReview(long reviewId) {
        try {
            Review review = reviewRepository.findById(reviewId).orElseThrow(() -> new LORException(ErrorCode.NOT_EXIST_REVIEW));
            review.softDeleted();
            reviewRepository.save(review); // 변경된 상태를 데이터베이스에 반영
            System.out.println("삭제 완료");
        } catch (IllegalArgumentException e) {
            throw new LORException(ErrorCode.FAIL_TO_DELETE_REVIEW);
        }
    }

}

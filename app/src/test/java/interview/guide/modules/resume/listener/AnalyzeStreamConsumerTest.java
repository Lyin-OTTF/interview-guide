package interview.guide.modules.resume.listener;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.model.ResumeAnalysisResponse;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.resume.service.ResumeGradingService;
import interview.guide.modules.resume.service.ResumeParseService;
import interview.guide.modules.resume.service.ResumePersistenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("简历分析消费者业务处理测试")
class AnalyzeStreamConsumerTest {

  private static final Long RESUME_ID = 9L;

  private static final String ATTEMPT_ID = "attempt-1";

  @Mock
  private RedisService redisService;

  @Mock
  private ResumeGradingService gradingService;

  @Mock
  private ResumePersistenceService persistenceService;

  @Mock
  private ResumeRepository resumeRepository;

  @Mock
  private ResumeParseService parseService;

  private AnalyzeStreamConsumer consumer;

  @BeforeEach
  void setUp() {
    consumer = new AnalyzeStreamConsumer(redisService, gradingService, persistenceService,
        resumeRepository, parseService);
  }

  private AnalyzeStreamConsumer.AnalyzePayload payload() {
    AnalyzeStreamConsumer.AnalyzePayload payload =
        new AnalyzeStreamConsumer.AnalyzePayload(RESUME_ID);
    payload.setAttemptId(ATTEMPT_ID);
    return payload;
  }

  private ResumeEntity resume() {
    ResumeEntity resume = new ResumeEntity();
    resume.setId(RESUME_ID);
    resume.setResumeText("候选人简历正文");
    return resume;
  }

  @Test
  @DisplayName("分析失败时向上抛出，交由消费者基类重试，且不落库占位报告")
  void shouldPropagateGradingFailureAndNotPersist() {
    when(resumeRepository.findById(RESUME_ID)).thenReturn(Optional.of(resume()));
    BusinessException failure = new BusinessException(
        ErrorCode.RESUME_ANALYSIS_FAILED, "简历分析失败：请稍后重试");
    when(gradingService.analyzeResume("候选人简历正文")).thenThrow(failure);

    assertThatThrownBy(() -> consumer.processBusiness(payload())).isSameAs(failure);
    verify(persistenceService, never()).saveAnalysisIfOwned(anyLong(), anyString(), any());
  }

  @Test
  @DisplayName("分析成功时按 attemptId 落库结果")
  void shouldPersistResultWhenAnalysisSucceeds() {
    ResumeEntity resume = resume();
    when(resumeRepository.findById(RESUME_ID)).thenReturn(Optional.of(resume));
    when(resumeRepository.heartbeatAnalyzeProcessing(eq(RESUME_ID), eq(ATTEMPT_ID), any()))
        .thenReturn(1);

    ResumeAnalysisResponse analysis = new ResumeAnalysisResponse(
        80,
        new ResumeAnalysisResponse.ScoreDetail(20, 15, 20, 12, 13),
        "结构清晰的简历",
        List.of("项目描述具体"),
        List.of(),
        "候选人简历正文");
    when(gradingService.analyzeResume("候选人简历正文")).thenReturn(analysis);

    consumer.processBusiness(payload());

    verify(persistenceService).saveAnalysisIfOwned(RESUME_ID, ATTEMPT_ID, analysis);
  }
}

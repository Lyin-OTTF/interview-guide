package interview.guide.modules.resume.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("简历评分服务测试")
class ResumeGradingServiceTest {

  @Mock
  private LlmProviderRegistry llmProviderRegistry;

  @Mock
  private StructuredOutputInvoker structuredOutputInvoker;

  private ResumeGradingService service;

  @BeforeEach
  void setUp() throws Exception {
    service = new ResumeGradingService(llmProviderRegistry, structuredOutputInvoker,
        new ResumeAnalysisProperties(), new DefaultResourceLoader());
  }

  @Test
  @DisplayName("LLM 调用失败时抛出业务异常，而不是返回 0 分占位结果")
  void shouldPropagateGradingFailureInsteadOfReturningDegradedResult() {
    BusinessException failure = new BusinessException(
        ErrorCode.RESUME_ANALYSIS_FAILED, "简历分析失败：请稍后重试");
    when(structuredOutputInvoker.invoke(any(), anyString(), anyString(), any(), any(),
        anyString(), anyString(), any())).thenThrow(failure);

    // 降级占位结果会让消费者把失败任务记为 COMPLETED，重试与恢复调度永远不会触发
    assertThatThrownBy(() -> service.analyzeResume("候选人简历正文"))
        .isSameAs(failure);
  }

  @Test
  @DisplayName("异常向上传播时保留原始错误码")
  void shouldKeepOriginalErrorCode() {
    when(structuredOutputInvoker.invoke(any(), anyString(), anyString(), any(), any(),
        anyString(), anyString(), any()))
        .thenThrow(new BusinessException(ErrorCode.RESUME_ANALYSIS_FAILED, "简历分析失败：请稍后重试"));

    assertThatThrownBy(() -> service.analyzeResume("候选人简历正文"))
        .isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.getCode()).isEqualTo(ErrorCode.RESUME_ANALYSIS_FAILED.getCode()));
  }

  @Test
  @DisplayName("非业务异常也不再被吞成占位结果")
  void shouldPropagateUnexpectedFailure() {
    IllegalStateException failure = new IllegalStateException("AI 客户端未初始化");
    when(structuredOutputInvoker.invoke(any(), anyString(), anyString(), any(), any(),
        anyString(), anyString(), any())).thenThrow(failure);

    assertThatThrownBy(() -> service.analyzeResume("候选人简历正文"))
        .isSameAs(failure);
  }
}

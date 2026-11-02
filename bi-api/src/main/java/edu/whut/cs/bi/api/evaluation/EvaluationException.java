package edu.whut.cs.bi.api.evaluation;

/**
 * 评定计算中出现可向调用方说明的业务错误。
 */
public class EvaluationException extends RuntimeException {

    public EvaluationException(String message) {
        super(message);
    }
}
package com.absmartly.sdk.jsonexpr.operators;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import com.absmartly.sdk.TestUtils;
import com.absmartly.sdk.jsonexpr.Evaluator;

public class OperatorTest extends TestUtils {
	Evaluator evaluator;

	@BeforeEach
	void setUp() {
		evaluator = mock(Evaluator.class);

		when(evaluator.evaluate(any())).thenAnswer(new Answer<Object>() {
			@Override
			public Object answer(InvocationOnMock invocation) throws Throwable {
				return invocation.getArgument(0);
			}
		});

		when(evaluator.booleanConvert(any())).thenAnswer(new Answer<Object>() {
			@Override
			public Object answer(InvocationOnMock invocation) throws Throwable {
				final Object arg = invocation.getArgument(0);
				return (arg == null) ? false : arg;
			}
		});

		when(evaluator.numberConvert(any())).thenAnswer(new Answer<Object>() {
			@Override
			public Object answer(InvocationOnMock invocation) throws Throwable {
				return invocation.getArgument(0);
			}
		});

		when(evaluator.stringConvert(any())).thenAnswer(new Answer<Object>() {
			@Override
			public Object answer(InvocationOnMock invocation) throws Throwable {
				final Object arg = invocation.getArgument(0);
				return arg.toString();
			}
		});

		when(evaluator.extractVar(any())).thenAnswer(new Answer<Object>() {
			@Override
			public Object answer(InvocationOnMock invocation) throws Throwable {
				if (invocation.getArgument(0).equals("a/b/c")) {
					return "abc";
				}
				return null;
			}
		});

		when(evaluator.compare(any(), any())).thenAnswer(new Answer<Object>() {
			@Override
			public Object answer(InvocationOnMock invocation) throws Throwable {
				final Object lhs = invocation.getArgument(0);
				final Object rhs = invocation.getArgument(1);

				if (lhs instanceof Boolean) {
					return Boolean.compare((Boolean) lhs, (Boolean) rhs);
				} else if (lhs instanceof Number) {
					return Double.compare(((Number) lhs).longValue(), ((Number) rhs).longValue());
				} else if (lhs instanceof String) {
					return ((String) lhs).compareTo((String) rhs);
				} else if (lhs.equals(rhs)) {
					return 0;
				}
				return null;
			}
		});
	}
}

package com.softwaremill.common.test.util;

import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Pawel Wrzeszcz (pawel . wrzeszcz [at] gmail . com)
 */
public class ExecutionTest {
	
	@Test
	public void shouldReturnExceptionForClosureWithException() throws Exception {
	    // Given
		final Exception exception = new Exception("msg");

	    Execution execution = new Execution() {

			@Override
			protected void execute() throws Exception {
				throw exception;
			}
		};

	    // When
		Exception result = execution.getException();

		// Then
		assertThat(result).isEqualTo(exception);
	}

	@Test
	public void shouldReturnNullForClosureWithoutException() throws Exception {
	    // Given
	    Execution execution = new Execution() {

			@Override
			protected void execute() throws Exception {
				// Valid code here
			}
		};

 		// When
		Exception result = execution.getException();

		// Then
		assertThat(result).isNull();
	}
}

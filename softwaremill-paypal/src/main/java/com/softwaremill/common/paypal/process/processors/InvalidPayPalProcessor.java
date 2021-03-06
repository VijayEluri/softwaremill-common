package com.softwaremill.common.paypal.process.processors;

import com.softwaremill.common.paypal.process.PayPalParameters;
import com.softwaremill.common.paypal.process.status.PayPalStatus;

public class InvalidPayPalProcessor extends AbstractPayPalProcessor {

    @Override
    public boolean accept(PayPalStatus status) {
        return status.isInvalid();
    }

    @Override
    public void process(PayPalStatus status, PayPalParameters parameters) {
        setErrorHappened();
        appendError("Paypal replied INVALID for a request.\n\n");
    }
}

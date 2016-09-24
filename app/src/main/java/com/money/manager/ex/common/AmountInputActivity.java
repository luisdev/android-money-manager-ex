/*
 * Copyright (C) 2012-2016 The Android Money Manager Ex Project Team
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.money.manager.ex.common;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.money.manager.ex.Constants;
import com.money.manager.ex.MoneyManagerApplication;
import com.money.manager.ex.R;
import com.money.manager.ex.core.FormatUtilities;
import com.money.manager.ex.core.NumericHelper;
import com.money.manager.ex.core.UIHelper;
import com.money.manager.ex.core.bundlers.MoneyBundler;
import com.money.manager.ex.currency.CurrencyService;
import com.money.manager.ex.domainmodel.Currency;
import com.shamanland.fonticon.FontIconView;

import net.objecthunter.exp4j.ExpressionBuilder;

import javax.inject.Inject;

import dagger.Lazy;
import icepick.State;
import info.javaperformance.money.Money;
import info.javaperformance.money.MoneyFactory;
import timber.log.Timber;

/**
 * Activity for the full-screen numeric input.
 * Additional functionality includes currency conversion.
 */

public class AmountInputActivity
    extends MmxBaseFragmentActivity {

    public static String EXTRA_CURRENCY_ID = "CurrencyId";
    public static String EXTRA_AMOUNT = "Amount";
    public static String EXTRA_ROUND_TO_CURRENCY = "RountToCurrencyDecimals";

    public static String RESULT_AMOUNT = "AmountEntered";

    /**
     * By default, round the number to the currency Scale. Set in the factory method.
     */
    @State boolean roundToCurrencyDecimals;
    //    @State String mRequestId;
    @State(MoneyBundler.class) Money mAmount;
    @State Integer mCurrencyId;
    /**
     * used to restore expression from saved instance state.
     */
    @State String mExpression;

    private int[] idButtonKeyNum = {
            R.id.buttonKeyNum0, R.id.buttonKeyNum1, R.id.buttonKeyNum2, R.id.buttonKeyNum3,
            R.id.buttonKeyNum4, R.id.buttonKeyNum5, R.id.buttonKeyNum6, R.id.buttonKeyNum7,
            R.id.buttonKeyNum8, R.id.buttonKeyNum9,
            R.id.buttonKeyNumDecimal,
    };
    private int[] idOperatorKeys = {
            R.id.buttonKeyAdd, R.id.buttonKeyDiv,
            R.id.buttonKeyLess, R.id.buttonKeyMultiplication,
            R.id.buttonKeyLeftParenthesis, R.id.buttonKeyRightParenthesis
    };

    @Inject CurrencyService mCurrencyService;
    @Inject Lazy<FormatUtilities> formatUtilitiesLazy;

    private Integer mDefaultColor;
    private TextView txtMain, txtTop;
    /**
     * Indicates that the user has already started typing. We should not replace the existing number
     * with the typed value but append the typed value to the existing number.
     */
    private boolean mStartedTyping = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_amount_input);

        MoneyManagerApplication.getApp().iocComponent.inject(this);

        if (savedInstanceState == null) {
            extractArguments();
        }

        initializeControls();
    }

    @Override
    public void onResume() {
        super.onResume();
        displayFormattedAmount();
    }

    private void initializeControls() {
        // set the decimal separator according to the locale
        setDecimalSeparator();

        // Numbers and Operators.
        View.OnClickListener numberClickListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Reset prior value/text (in some cases only).
                String existingValue = txtMain.getText().toString();
                if (!mStartedTyping) {
                    existingValue = "";
                    mStartedTyping = true;
                }

                txtMain.setText(existingValue.concat(((Button) v).getText().toString()));
                evalExpression();
            }
        };
        for (int id : idButtonKeyNum) {
            Button button = (Button) findViewById(id);
            button.setOnClickListener(numberClickListener);
        }

        View.OnClickListener operatorClickListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String existingValue = txtMain.getText().toString();
                mStartedTyping = true;

                txtMain.setText(existingValue.concat(((Button) v).getText().toString()));
                evalExpression();
            }
        };
        for (int id : idOperatorKeys) {
            Button button = (Button) findViewById(id);
            button.setOnClickListener(operatorClickListener);
        }

        // Clear button. 'C'
        Button clearButton = (Button) findViewById(R.id.buttonKeyClear);
        clearButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mStartedTyping = true;
                txtMain.setText("");
                evalExpression();
            }
        });

        // Equals button '='
        Button buttonKeyEquals = (Button) findViewById(R.id.buttonKeyEqual);
        buttonKeyEquals.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // this is called only to reset the warning colour in the top box, if any.
                evalExpression();
                showAmountInEntryField();

                // set result and return
                Intent result = new Intent();
                result.putExtra(RESULT_AMOUNT, mAmount.toString());
                setResult(Activity.RESULT_OK, result);
                finish();
            }
        });

        // Delete button '<='
        FontIconView deleteButton = (FontIconView) findViewById(R.id.deleteButton);
        if (deleteButton != null) {
            deleteButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mStartedTyping = true;

                    String currentNumber = txtMain.getText().toString();
                    currentNumber = deleteLastCharacterFrom(currentNumber);
                    txtMain.setText(currentNumber);

                    evalExpression();
                }
            });
        }

        // Amounts
        txtTop = (TextView) findViewById(R.id.textViewTop);
        mDefaultColor = txtTop.getCurrentTextColor();

        txtMain = (TextView) findViewById(R.id.textViewMain);
        if (!TextUtils.isEmpty(mExpression)) {
            txtMain.setText(mExpression);
        } else {
            showAmountInEntryField();
        }

        // evaluate the expression initially, in case there is an existing amount passed to the binaryDialog.
        evalExpression();

        // Dialog

//        MaterialDialog.Builder builder = new MaterialDialog.Builder(this)
//                .customView(view, false)
//                .cancelable(false)
//                .onPositive(new MaterialDialog.SingleButtonCallback() {
//                    @Override
//                    public void onClick(@NonNull MaterialDialog dialog, @NonNull DialogAction which) {
//                        if (!evalExpression()) return;
//
//                        EventBus.getDefault().post(new AmountEnteredEvent(mRequestId, getAmount()));
//
//                        dialog.dismiss();
//                    }
//                })
//                .onNegative(new MaterialDialog.SingleButtonCallback() {
//                    @Override
//                    public void onClick(@NonNull MaterialDialog dialog, @NonNull DialogAction which) {
//                        dialog.dismiss();
//                    }
//                })
//                .autoDismiss(false)
//                .negativeText(android.R.string.cancel)
//                .positiveText(android.R.string.ok);
//
//        return builder.show();
    }

    /**
     * Displays the expression result in the top text box. This is a formatted number in the
     * given currency.
     */
    public void displayFormattedAmount() {
        String result = getFormattedAmount();

        txtTop.setText(result);
    }

    /**
     * Evaluate the entered expression and recalculate the resulting amount.
     * @return Boolean indicating whether the operation was successful or not.
     */
    public boolean evalExpression() {
        String exp = txtMain.getText().toString();
        NumericHelper helper = new NumericHelper(this);

        exp = helper.cleanUpNumberString(exp);

        if (exp.length() > 0) {
            try {
                Double result = new ExpressionBuilder(exp).build().evaluate();

                int precision = getPrecision();
                mAmount = MoneyFactory.fromString(Double.toString(result))
                        .truncate(precision);
            } catch (IllegalArgumentException ex) {
                // Just display the last valid value.
                displayFormattedAmount();
                // Use the warning colour.
                txtTop.setTextColor(getResources().getColor(R.color.material_amber_800));

                return false;
            } catch (Exception e) {
                Timber.e(e, "evaluating expression");
            }
        } else {
            mAmount = MoneyFactory.fromString("0");
        }

        displayFormattedAmount();
        txtTop.setTextColor(mDefaultColor);
        return true;
    }

    /**
     * Get amount formatted in the formatting currency.
     * @return String Amount formatted in the given currency.
     */
    public String getFormattedAmount() {
        String result = null;
        FormatUtilities format = formatUtilitiesLazy.get();

        // No currency. Use locale preferences.
        if (mCurrencyId == null) {
            result = format.formatWithLocale(mAmount);
        }

        // Use currency preferences but ignore the decimals.
        if (!roundToCurrencyDecimals) {
            // ignore the currency preferences but show the symbol.
            result = format.formatNumberIgnoreDecimalCount(mAmount, mCurrencyId);
        }

        // default format, use currency preferences.
        if (result == null) {
            result = mCurrencyService.getCurrencyFormatted(mCurrencyId, mAmount);
        }

        return result;
    }

    /*
        private
    */

    private int getPrecision() {
        // if using a currency and currency precision is required, use that.
        if (!this.roundToCurrencyDecimals || this.mCurrencyId == null) return Constants.DEFAULT_PRECISION;

        Currency currency = this.mCurrencyService.getCurrency(mCurrencyId);
        if (currency == null) return Constants.DEFAULT_PRECISION;

        // get precision from the currency
        NumericHelper helper = new NumericHelper(this);
        return helper.getNumberOfDecimals(currency.getScale());
    }

    private String deleteLastCharacterFrom(String number) {
        // check length
        if (number.length() <= 0) return number;

        // first cut-off the last digit
        number = number.substring(0, number.length() - 1);

        // Should we check if the next character is the decimal separator. (?)

        // Handle deleting the last number - set the remaining value to 0.
        if (TextUtils.isEmpty(number)) {
            number = "0";
        }

        return number;
    }

    private void extractArguments() {
        Intent intent = getIntent();
        if (intent == null) return;

        mCurrencyId = intent.getIntExtra(EXTRA_CURRENCY_ID, Constants.NOT_SET);
        roundToCurrencyDecimals = intent.getBooleanExtra(EXTRA_ROUND_TO_CURRENCY, true);

        String value = intent.getStringExtra(EXTRA_AMOUNT);
        if (!TextUtils.isEmpty(value)) {
            NumericHelper numericHelper = new NumericHelper(this);
            Currency currency = mCurrencyService.getCurrency(mCurrencyId);

            Money amount = MoneyFactory.fromString(value);

            if (currency != null && this.roundToCurrencyDecimals) {
                mAmount = numericHelper.truncateToCurrency(amount, currency);
            } else {
                // no currency and no base currency set.
                mAmount = amount;
            }

        }
    }

//    private void restoreSavedInstanceState(Bundle savedInstanceState) {
//        if (savedInstanceState.containsKey(KEY_REQUEST_ID)) {
//            mRequestId = savedInstanceState.getString(KEY_REQUEST_ID);
//        }
//        if (savedInstanceState.containsKey(KEY_AMOUNT)) {
//            mAmount = MoneyFactory.fromString(savedInstanceState.getString(KEY_AMOUNT));
//        }
//        if (savedInstanceState.containsKey(KEY_CURRENCY_ID)) {
//            mCurrencyId = savedInstanceState.getInt(KEY_CURRENCY_ID);
//        }
//        if (savedInstanceState.containsKey(KEY_EXPRESSION)) {
//            mExpression = savedInstanceState.getString(KEY_EXPRESSION);
//        }
//    }

    /**
     * Set the decimal separator to the base currency's separator.
     */
    private void setDecimalSeparator() {
        Button separatorButton = (Button) findViewById(R.id.buttonKeyNumDecimal);

        String separator = this.formatUtilitiesLazy.get().getDecimalSeparatorForAppLocale();

        separatorButton.setText(separator);
    }

    private boolean isCurrencySet() {
        return this.mCurrencyId != null && this.mCurrencyId != Constants.NOT_SET;
    }

    private Money getAmount() {
        Money result;

        // to round or not? Handle case when no base currency set.
        if (this.roundToCurrencyDecimals && isCurrencySet()) {
            NumericHelper numericHelper = new NumericHelper(this);
            Currency currency = mCurrencyService.getCurrency(mCurrencyId);
            result = numericHelper.truncateToCurrency(mAmount, currency);
        } else {
            result = mAmount;
        }
        return result;
    }

    private String getFormattedAmountForEditing(Money amount) {
        if (amount == null) return "0";

        String result;
        Currency displayCurrency = mCurrencyService.getCurrency(mCurrencyId);

        if (displayCurrency != null) {
            if(roundToCurrencyDecimals) {
                // use decimals from the display currency.
                // but decimal and group separators from the base currency.
                result = formatUtilitiesLazy.get().format(amount, displayCurrency.getScale(),
                        formatUtilitiesLazy.get().getDecimalSeparatorForAppLocale(),
                        formatUtilitiesLazy.get().getGroupingSeparatorForAppLocale());

            } else {
                // Use default precision and no currency markup.
                result = formatUtilitiesLazy.get().formatNumber(amount, Constants.DEFAULT_PRECISION,
                        displayCurrency.getDecimalSeparator(), displayCurrency.getGroupSeparator(),
                        null, null);
            }
        } else {
            result = formatUtilitiesLazy.get().formatWithLocale(amount);
        }

        return result;
    }

//    private boolean getRoundToCurrencyDecimals() {
//        return getArguments().getBoolean(ARG_ROUNDING);
//    }

//    private void initializeNewDialog() {
//        // not in restored state. new dialog
//
//        // Display the existing amount, if any has been passed into the binaryDialog.
//        NumericHelper numericHelper = new NumericHelper(this);
//        Currency currency = mCurrencyService.getCurrency(mCurrencyId);
//
//        Money amount = MoneyFactory.fromString(getArguments().getString(KEY_AMOUNT));
//        if (currency != null && this.roundToCurrencyDecimals) {
//            mAmount = numericHelper.truncateToCurrency(amount, currency);
//        } else {
//            // no currency and no base currency set.
//            mAmount = amount;
//        }
//    }

//    private void restoreArguments() {
//        Bundle args = getArguments();
//
//        this.mRequestId = args.getString(KEY_REQUEST_ID);
//        this.mCurrencyId = args.getInt(KEY_CURRENCY_ID);
//        this.roundToCurrencyDecimals = args.getBoolean(ARG_ROUNDING);
//    }

    private void showAmountInEntryField() {
        // Get the calculated amount in default locale and display in the main box.
        String amount = getFormattedAmountForEditing(mAmount);
        txtMain.setText(amount);
    }

}

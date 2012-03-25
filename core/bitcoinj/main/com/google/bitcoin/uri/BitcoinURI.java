/*
 * Copyright 2012 the original author or authors.
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 */

package com.google.bitcoin.uri;

import com.google.bitcoin.core.Address;
import com.google.bitcoin.core.AddressFormatException;
import com.google.bitcoin.core.NetworkParameters;
import com.google.bitcoin.core.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <p>
 * Provides a standard implementation of a Bitcoin URI with support for the
 * following:
 * </p>
 * <ul>
 * <li>URLEncoded URIs (as passed in by IE on the command line)</li>
 * <li>BIP21 names (including the "req-" prefix handling requirements)</li>
 * </ul>
 * <h2>Accepted formats</h2>
 * <p>
 * The following input forms are accepted
 * </p>
 * <ul>
 * <li>{@code bitcoin:<address>}</li>
 * <li>{@code bitcoin:<address>?<name1>=<value1>&<name2>=<value2>} with multiple
 * additional name/value pairs</li>
 * </ul>
 * <p>
 * The name/value pairs are processed as follows:
 * </p>
 * <ul>
 * <li>URL encoding is stripped and treated as UTF-8</li>
 * <li>names prefixed with {@code req-} are treated as required and if unknown
 * or conflicting cause a parse exception</li>
 * <li>Unknown names not prefixed with {@code req-} are added to a Map, accessible 
 * by parameter name</li>
 * <li>Known names not prefixed with {@code req-} are processed unless they are
 * malformed</li>
 * </ul>
 * <p>
 * The following names are known and have the following formats
 * </p>
 * <ul>
 * <li>{@code amount} decimal value to 8 dp (e.g. 0.12345678) <b>Note that the
 * exponent notation is not supported any more</b></li>
 * <li>{@code label} any URL encoded alphanumeric</li>
 * <li>{@code message} any URL encoded alphanumeric</li>
 * </ul>
 * 
 * @author Andreas Schildbach (initial code)
 * @author Jim Burton (enhancements for MultiBit)
 * @author Gary Rowe (BIP21 support)
 * @see <a href="https://en.bitcoin.it/wiki/BIP_0021">BIP 0021</a>
 */
public class BitcoinURI {
    /**
     * Provides logging for this class
     */
    private static final Logger log = LoggerFactory.getLogger(BitcoinURI.class);

    // Not worth turning into an enum
    private static final String FIELD_MESSAGE = "message";
    private static final String FIELD_LABEL = "label";
    private static final String FIELD_AMOUNT = "amount";
    private static final String FIELD_ADDRESS = "address";

    public static final String BITCOIN_SCHEME = "bitcoin";
    private static final String ENCODED_SPACE_CHARACTER = "%20";
    private static final String AMPERSAND_SEPARATOR = "&";
    private static final String QUESTION_MARK_SEPARATOR = "?";
    private static final String COLON_SEPARATOR = ":";

    /**
     * Contains all the parameters in the order in which they were processed
     */
    private final Map<String, Object> parameterMap = new LinkedHashMap<String, Object>();

    /**
     * @param networkParameters
     *            The BitCoinJ network parameters that determine which network
     *            the URI is from
     * @param input
     *            The raw URI data to be parsed (see class comments for accepted
     *            formats)
     * @throws BitcoinURIParseException
     *            If the input fails Bitcoin URI syntax and semantic checks
     */
    public BitcoinURI(NetworkParameters networkParameters, String input) {
        // Basic validation
        if (networkParameters == null) {
            throw new IllegalArgumentException("NetworkParameters cannot be null");
        }
        if (input == null) {
            throw new IllegalArgumentException("Input cannot be null");
        }

        log.debug("Attempting to parse '{}' for {}", input, networkParameters.port == 8333 ? "prodNet" : "testNet");

        // URI validation
        if (!input.startsWith(BITCOIN_SCHEME)) {
            throw new BitcoinURIParseException("Bad scheme - expecting '" + BITCOIN_SCHEME + "'");
        }

        // Attempt to form the URI (fail fast syntax checking to official standards)
        URI uri;
        try {
            uri = new URI(input);
        } catch (URISyntaxException e) {
            throw new BitcoinURIParseException("Bad URI syntax", e);
        }

        // URI is formed as  bitcoin:<address>?<query parameters>
        
        // Remove the bitcoin scheme
        // (Note: getSchemeSpecificPart() is not used as it unescapes the label and parse then fails.
        // For instance with : bitcoin:129mVqKUmJ9uwPxKJBnNdABbuaaNfho4Ha?amount=0.06&label=Tom%20%26%20Jerry
        // the & (%26) in Tom and Jerry gets interpreted as a separator and the label then gets parsed as 'Tom ' instead of 'Tom & Jerry')
        String schemeSpecificPart = "";
        if (uri.toString().startsWith(BITCOIN_SCHEME + COLON_SEPARATOR)) {
            schemeSpecificPart = uri.toString().substring(BITCOIN_SCHEME.length() + 1);
        }

        // Split off the address from the rest of the query parameters
        String[] addressSplitTokens = schemeSpecificPart.split("\\?");
        if (addressSplitTokens.length == 0 || "".equals(addressSplitTokens[0])) {
            throw new BitcoinURIParseException("Missing address");
        }
        String addressToken = addressSplitTokens[0];

        String[] nameValuePairTokens;
        if (addressSplitTokens.length == 1) {
            // only an address is specified - use an empty '<name>=<value>' token array 
            nameValuePairTokens = new String[] {};
        } else {
            if (addressSplitTokens.length == 2) {
                // split into '<name>=<value>' tokens
                nameValuePairTokens = addressSplitTokens[1].split("&");
            } else {
                throw new BitcoinURIParseException("Too many question marks in URI '" + input + "'");                
            }
        }

        // Attempt to parse the rest of the URI parameters
        parseParameters(networkParameters, addressToken, nameValuePairTokens);
    }

    /**
     * @param networkParameters
     *            The network parameters
     * @param nameValuePairTokens
     *            The tokens representing the name value pairs (assumed to be
     *            separated by '=' e.g. 'amount=0.2')
     */
    private void parseParameters(NetworkParameters networkParameters, String addressToken, String[] nameValuePairTokens) {
        // Attempt to parse the addressToken as a Bitcoin address for this network
        try {
            Address address = new Address(networkParameters, addressToken);
            putWithValidation(FIELD_ADDRESS, address);
        } catch (final AddressFormatException e) {
            throw new BitcoinURIParseException("Bad address", e);
        }
        
        // Attempt to decode the rest of the tokens into a parameter map
        for (int i = 0; i < nameValuePairTokens.length; i++) {

            String[] tokens = nameValuePairTokens[i].split("=");
            if (tokens.length != 2 || "".equals(tokens[0])) {
                throw new BitcoinURIParseException("Malformed Bitcoin URI - cannot parse name value pair '" + nameValuePairTokens[i] + "'");
            }

            String nameToken = tokens[0].toLowerCase();
            String valueToken = tokens[1];

            // Parse the amount
            if (FIELD_AMOUNT.equals(nameToken)) {
                // Decode the amount (contains an optional decimal component to 8dp)
                try {
                    BigInteger amount = Utils.toNanoCoins(valueToken);
                    putWithValidation(FIELD_AMOUNT, amount);
                } catch (NumberFormatException e) {
                    throw new OptionalFieldValidationException("'" + valueToken + "' value is not a valid amount", e);
                }
            } else {
                if (nameToken.startsWith("req-")) {
                    // A required parameter that we do not know about
                    throw new RequiredFieldValidationException("'" + nameToken + "' is required but not known, this URI is not valid");
                } else {
                    // Known fields and unknown parameters that are optional
                    try {
                        putWithValidation(nameToken, URLDecoder.decode(valueToken, "UTF-8"));
                    } catch (UnsupportedEncodingException e) {
                        // should not happen as UTF-8 is valid encoding
                        throw new RuntimeException(e);
                    }
                }
            }
        }

        // Note to the future : when you want to implement 'req-expires' have a look at commit 410a53791841 which had it in
    }

    /**
     * <p>
     * Put the value against the key in the map checking for duplication.
     * This avoids address field overwrite etc.
     * </p>
     * 
     * @param key
     *            The key for the map
     * @param value
     *            The value to store
     */
    private void putWithValidation(String key, Object value) {
        if (parameterMap.containsKey(key)) {
            throw new BitcoinURIParseException("'" + key + "' is duplicated, URI is invalid");
        } else {
            parameterMap.put(key, value);
        }
    }

    /**
     * @return The Bitcoin Address from the URI
     */
    public Address getAddress() {
        return (Address) parameterMap.get(FIELD_ADDRESS);
    }

    /**
     * @return The amount name encoded using a pure integer value based at
     *         10,000,000 units is 1 BTC. May be null if no amount is specified
     */
    public BigInteger getAmount() {
        return (BigInteger) parameterMap.get(FIELD_AMOUNT);
    }

    /**
     * @return The label from the URI.
     */
    public String getLabel() {
        return (String) parameterMap.get(FIELD_LABEL);
    }

    /**
     * @return The message from the URI.
     */
    public String getMessage() {
        return (String) parameterMap.get(FIELD_MESSAGE);
    }
    
    /**
     * @param name The name of the parameter
     * @return The parameter value, or null if not present
     */
    public Object getParameterByName(String name) {
        return parameterMap.get(name);
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder("BitcoinURI[");
        boolean first = true;
        for (Map.Entry<String, Object> entry : parameterMap.entrySet()) {
            if (first) {
                first = false;
            } else {
                builder.append(",");
            }
            builder.append("'").append(entry.getKey()).append("'=").append("'").append(entry.getValue().toString()).append("'");
        }
        builder.append("]");
        return builder.toString();
    }

    /**
     * <p>
     * Simple Bitcoin URI builder using known good fields
     * </p>
     * 
     * @param address
     *            The Bitcoin address
     * @param amount
     *            The amount in nanocoins (decimal)
     * @param label
     *            A label
     * @param message
     *            A message
     * @return A String containing the Bitcoin URI
     */
    public static String convertToBitcoinURI(Address address, BigInteger amount, String label, String message) {
        if (address == null) {
            throw new IllegalArgumentException("Missing address");
        }
        
        if (amount != null && amount.compareTo(BigInteger.ZERO) < 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        
        StringBuilder builder = new StringBuilder();
        builder.append(BITCOIN_SCHEME).append(COLON_SEPARATOR).append(address.toString());
        
        boolean questionMarkHasBeenOutput = false;
        
        if (amount != null) {
            builder.append(QUESTION_MARK_SEPARATOR).append(FIELD_AMOUNT).append("=");
            builder.append(Utils.bitcoinValueToPlainString(amount));
            questionMarkHasBeenOutput = true;
        }
        
        if (label != null && !"".equals(label)) {
            if (questionMarkHasBeenOutput) {
                builder.append(AMPERSAND_SEPARATOR);
            } else {
                builder.append(QUESTION_MARK_SEPARATOR);                
                questionMarkHasBeenOutput = true;
            }
            builder.append(FIELD_LABEL).append("=").append(encodeURLString(label));
        }
        
        if (message != null && !"".equals(message)) {
            if (questionMarkHasBeenOutput) {
                builder.append(AMPERSAND_SEPARATOR);
            } else {
                builder.append(QUESTION_MARK_SEPARATOR);                
                questionMarkHasBeenOutput = true;
            }
            builder.append(FIELD_MESSAGE).append("=").append(encodeURLString(message));
        }
        
        return builder.toString();
    }

    /**
     * <p>
     * Encode a string using URL encoding
     * </p>
     * 
     * @param stringToEncode
     *            The string to URL encode
     */
    static String encodeURLString(String stringToEncode) {
        try {
            return java.net.URLEncoder.encode(stringToEncode, "UTF-8").replace("+", ENCODED_SPACE_CHARACTER);
        } catch (UnsupportedEncodingException e) {
            // should not happen - UTF-8 is a valid encoding
            throw new RuntimeException(e);
        }
    }
}

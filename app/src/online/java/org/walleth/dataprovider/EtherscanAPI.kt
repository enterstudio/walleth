package org.walleth.dataprovider


import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import org.kethereum.model.Address
import org.ligi.kaxt.letIf
import org.ligi.tracedroid.logging.Log
import org.walleth.BuildConfig
import org.walleth.data.AppDatabase
import org.walleth.data.balances.Balance
import org.walleth.data.balances.upsertIfNewerBlock
import org.walleth.data.networks.NetworkDefinition
import org.walleth.data.networks.NetworkDefinitionProvider
import org.walleth.data.tokens.CurrentTokenProvider
import org.walleth.data.tokens.isRootToken
import org.walleth.kethereum.etherscan.getEtherScanAPIBaseURL
import java.io.IOException
import java.math.BigInteger
import java.security.cert.CertPathValidatorException

class EtherscanAPI(private val networkDefinitionProvider: NetworkDefinitionProvider,
                   private val appDatabase: AppDatabase,
                   private val tokenProvider: CurrentTokenProvider,
                   private val okHttpClient: OkHttpClient) {

    private var lastSeenTransactionsBlock = 0L
    private var lastSeenBalanceBlock = 0L

    fun queryTransactions(addressHex: String) {
        networkDefinitionProvider.value?.let { currentNetwork ->
            val requestString = "module=account&action=txlist&address=$addressHex&startblock=$lastSeenTransactionsBlock&endblock=${lastSeenBalanceBlock + 1L}&sort=asc"

            try {
                val etherscanResult = getEtherscanResult(requestString, currentNetwork)
                if (etherscanResult != null && etherscanResult.has("result")) {
                    val jsonArray = etherscanResult.getJSONArray("result")
                    val newTransactions = parseEtherScanTransactions(jsonArray, currentNetwork.chain)

                    lastSeenTransactionsBlock = newTransactions.highestBlock

                    newTransactions.list.forEach {

                        val oldEntry = appDatabase.transactions.getByHash(it.hash)
                        if (oldEntry == null || oldEntry.transactionState.isPending) {
                            appDatabase.transactions.upsert(it)
                        }
                    }

                }
            } catch (e: JSONException) {
                Log.w("Problem with JSON from EtherScan: " + e.message)
            }
        }
    }

    fun getEtherscanResult(requestString: String, networkDefinition: NetworkDefinition) = try {
        getEtherscanResult(requestString, networkDefinition, false)
    } catch (e: CertPathValidatorException) {
        getEtherscanResult(requestString, networkDefinition, true)
    }

    private fun getEtherscanResult(requestString: String, networkDefinition: NetworkDefinition, httpFallback: Boolean): JSONObject? {
        val baseURL = getEtherScanAPIBaseURL(networkDefinition.chain).letIf(httpFallback) {
            replace("https://", "http://") // :-( https://github.com/walleth/walleth/issues/134 )
        }
        val urlString = "$baseURL/api?$requestString&apikey=$" + BuildConfig.ETHERSCAN_APIKEY
        val url = Request.Builder().url(urlString).build()
        val newCall: Call = okHttpClient.newCall(url)

        try {
            val resultString = newCall.execute().body().use { it?.string() }
            resultString.let {
                return JSONObject(it)
            }
        } catch (ioe: IOException) {
            ioe.printStackTrace()
        } catch (jsonException: JSONException) {
            jsonException.printStackTrace()
        }

        return null
    }

    private fun reset() {
        lastSeenBalanceBlock = 0L
        lastSeenTransactionsBlock = 0L
    }


    fun queryEtherscanForBalance(addressHex: String) {

        networkDefinitionProvider.value?.let { currentNetwork ->
            val currentToken = tokenProvider.getCurrent()
            val etherscanResult = getEtherscanResult("module=proxy&action=eth_blockNumber", currentNetwork)

            if (etherscanResult?.has("result") != true) {
                Log.w("Cannot parse " + etherscanResult)
                return
            }
            val blockNum = etherscanResult.getString("result")?.replace("0x", "")?.toLongOrNull(16)

            if (blockNum != null) {
                lastSeenBalanceBlock = blockNum

                val balanceString = if (currentToken.isRootToken()) {
                    getEtherscanResult("module=account&action=balance&address=$addressHex&tag=latest", currentNetwork)?.getString("result")

                } else {
                    getEtherscanResult("module=account&action=tokenbalance&contractaddress=${currentToken.address}&address=$addressHex&tag=latest", currentNetwork)?.getString("result")

                }

                if (balanceString != null) {
                    try {
                        appDatabase.balances.upsertIfNewerBlock(
                                Balance(address = Address(addressHex),
                                        block = blockNum,
                                        balance = BigInteger(balanceString),
                                        tokenAddress = currentToken.address,
                                        chain = currentNetwork.chain
                                )
                        )
                    } catch (e: NumberFormatException) {
                        Log.i("could not parse number $balanceString")
                    }
                }
            }
        }
    }

}
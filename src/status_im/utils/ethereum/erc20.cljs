(ns status-im.utils.ethereum.erc20
  "
  Helper functions to interact with [ERC20](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-20-token-standard.md) smart contract

  Example

  Contract: https://ropsten.etherscan.io/address/0x29b5f6efad2ad701952dfde9f29c960b5d6199c5#readContract
  Owner: https://ropsten.etherscan.io/token/0x29b5f6efad2ad701952dfde9f29c960b5d6199c5?a=0xa7cfd581060ec66414790691681732db249502bd

  With a running node on Ropsten:
  (let [web3 (:web3 @re-frame.db/app-db)
        contract \"0x29b5f6efad2ad701952dfde9f29c960b5d6199c5\"
        address \"0xa7cfd581060ec66414790691681732db249502bd\"]
    (erc20/balance-of web3 contract address println))

  => 29166666
  "
  (:require [status-im.utils.ethereum.core :as ethereum]
            [status-im.native-module.core :as status]
            [status-im.utils.ethereum.tokens :as tokens]
            [status-im.constants :as constants]
            [status-im.utils.datetime :as datetime])
  (:refer-clojure :exclude [name symbol]))

(defn name [web3 contract cb]
  (ethereum/call web3 (ethereum/call-params contract "name()") cb))

(defn symbol [web3 contract cb]
  (ethereum/call web3 (ethereum/call-params contract "symbol()") cb))

(defn decimals [web3 contract cb]
  (ethereum/call web3 (ethereum/call-params contract "decimals()") cb))

(defn total-supply [web3 contract cb]
  (ethereum/call web3
                 (ethereum/call-params contract "totalSupply()")
                 #(cb %1 (ethereum/hex->bignumber %2))))

(defn balance-of [web3 contract address cb]
  (ethereum/call web3
                 (ethereum/call-params contract "balanceOf(address)" (ethereum/normalized-address address))
                 #(cb %1 (ethereum/hex->bignumber %2))))

(defn transfer [web3 contract from address value params cb]
  (ethereum/send-transaction web3
                             (merge (ethereum/call-params contract "transfer(address,uint256)" (ethereum/normalized-address address) (ethereum/int->hex value))
                                    {:from from}
                                    params)
                             #(cb %1 (ethereum/hex->boolean %2))))

(defn transfer-from [web3 contract from-address to-address value cb]
  (ethereum/call web3
                 (ethereum/call-params contract "transferFrom(address,address,uint256)" (ethereum/normalized-address from-address) (ethereum/normalized-address to-address) (ethereum/int->hex value))
                 #(cb %1 (ethereum/hex->boolean %2))))

(defn approve [web3 contract address value cb]
  (ethereum/call web3
                 (ethereum/call-params contract "approve(address,uint256)" (ethereum/normalized-address address)  (ethereum/int->hex value))
                 #(cb %1 (ethereum/hex->boolean %2))))

(defn allowance [web3 contract owner-address spender-address cb]
  (ethereum/call web3
                 (ethereum/call-params contract "allowance(address,address)" (ethereum/normalized-address owner-address) (ethereum/normalized-address spender-address))
                 #(cb %1 (ethereum/hex->bignumber %2))))

(defn- parse-json [s]
  (try
    (let [res (-> s
                  js/JSON.parse
                  (js->clj :keywordize-keys true))]
      (if (= (:error res) "")
        {:result true}
        res))
    (catch :default e
      {:error (.-message e)})))

(defn- add-padding [address]
  (when address
    (str "0x000000000000000000000000" (subs address 2))))

(defn- remove-padding [topic]
  (if topic
    (str "0x" (subs topic 26))))

(defn- parse-transaction-entry [block-number chain direction entries]
  (into {}
        (for [entry entries]
          (let [token (->> entry :address (tokens/address->token chain))]
            [(:transactionHash entry)
             {:block         (-> entry :blockNumber ethereum/hex->int str)
              :hash          (:transactionHash entry)
              :symbol        (:symbol token)
              :from          (-> entry :topics second remove-padding)
              :to            (-> entry :topics last remove-padding)
              :value         (-> entry :data ethereum/hex->bignumber)
              :type          direction

              :confirmations (str (- block-number (-> entry :blockNumber ethereum/hex->int)))

              :gas-price     nil
              :nonce         nil
              :data          nil

              :gas-limit     nil
              ;; NOTE(goranjovic) - timestamp is mocked to the current time so that the transaction is shown at the
              ;; top of transaction history list between the moment when transfer event was detected and actual
              ;; timestamp was retrieved from block info.
              :timestamp     (str (datetime/timestamp))

              :gas-used      nil

              ;; NOTE(goranjovic) - metadata on the type of token in question: contains name, symbol, decimas, address.
              :token         token

              ;; NOTE(goranjovic) - just a flag we need when we merge this entry with the existing entry in
              ;; the app, e.g. transaction info with gas details, or a previous transfer entry with old
              ;; confirmations count.
              :transfer      true}]))))

(defn- response-handler [block-number chain direction error-fn success-fn]
  (fn handle-response
    ([response]
     (let [{:keys [error result]} (parse-json response)]
       (handle-response error result)))
    ([error result]
     (if error
       (error-fn error)
       (success-fn (parse-transaction-entry block-number chain direction result))))))

;;
;; Here we are querying event logs for Transfer events.
;;
;; The parameters are as follows:
;; - address - token smart contract address
;; - fromBlock - we need to specify it, since default is latest
;; - topics[0] - hash code of the Transfer event signature
;; - topics[1] - address of token sender with leading zeroes padding up to 32 bytes
;; - topics[2] - address of token sender with leading zeroes padding up to 32 bytes
;;

(defn get-token-transfer-logs
  ;; NOTE(goranjovic): here we cannot use web3 since events don't work with infura
  [block-number network contracts direction address cb]
  (let [chain (ethereum/network->chain-keyword network)
        [from to] (if (= :inbound direction)
                    [nil (ethereum/normalized-address address)]
                    [(ethereum/normalized-address address) nil])
        args {:jsonrpc "2.0"
              :id      2
              :method  constants/web3-get-logs
              :params  [{:address   contracts
                         :fromBlock "0x0"
                         :topics    [constants/event-transfer-hash
                                     (add-padding from)
                                     (add-padding to)]}]}
        payload (.stringify js/JSON (clj->js args))]
    (status/call-web3-private payload
                              (response-handler block-number chain direction ethereum/handle-error cb))))

(defn get-token-transactions
  [web3 network contracts direction address cb]
  (ethereum/get-block-number web3
                             #(get-token-transfer-logs % network contracts direction address cb)))

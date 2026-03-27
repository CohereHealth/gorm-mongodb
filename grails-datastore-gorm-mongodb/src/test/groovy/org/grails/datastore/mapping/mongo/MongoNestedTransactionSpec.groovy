package org.grails.datastore.mapping.mongo

import grails.gorm.annotation.Entity
import grails.gorm.tests.GormDatastoreSpec
import org.springframework.stereotype.Service

class MongoNestedTransactionSpec extends GormDatastoreSpec {

    @Override
    List getDomainClasses() {
        [Account, Transaction]
    }

    def "test nested service calls with native transactions"() {
        given:
        def accountService = new AccountService()
        def transactionService = new TransactionService()
        
        when: "nested service calls within native transaction"
        def result = Account.withNativeTransaction { session ->
            def account = accountService.createAccount("John", 1000.0)
            transactionService.processTransaction(account.id, -100.0)
            return Account.get(account.id)
        }
        
        then:
        result.balance == 900.0
        Transaction.count() == 1
        
        when: "exception in nested call causes rollback"
        Account.withNativeTransaction { session ->
            def account = accountService.createAccount("Jane", 500.0)
            transactionService.processTransaction(account.id, -600.0) // Should fail
        }
        
        then:
        thrown(RuntimeException)
        Account.countByName("Jane") == 0 // Should be rolled back
    }
}

@Entity
class Account {
    String name
    Double balance
}

@Entity 
class Transaction {
    Long accountId
    Double amount
}

@Service
class AccountService {
    Account createAccount(String name, Double balance) {
        return new Account(name: name, balance: balance).save(flush: true)
    }
}

@Service
class TransactionService {
    void processTransaction(Long accountId, Double amount) {
        def account = Account.get(accountId)
        if (account.balance + amount < 0) {
            throw new RuntimeException("Insufficient funds")
        }
        
        account.balance += amount
        account.save(flush: true)
        
        new Transaction(accountId: accountId, amount: amount).save(flush: true)
    }
}
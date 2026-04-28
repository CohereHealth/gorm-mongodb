// Place your Spring DSL code here
beans = {
    // Create an alias so Spring's TransactionInterceptor can find the transaction manager
    // when it looks up by the "nativeTransaction" qualifier
    springConfig.addAlias('nativeTransaction', 'transactionManager')
}

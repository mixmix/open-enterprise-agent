@RFC0453 @AIP20
Feature: Issue Credentials Protocol

@TEST_ATL-3849
Scenario: Issuing credential with published PRISM DID to unpublished PRISM DID
  Given Acme and Bob have an existing connection
  When Acme creates unpublished DID
  And He publishes DID to ledger
  And Bob creates unpublished DID
  And Acme offers a credential to Bob with "short" form DID
  And Bob receives the credential offer and accepts
  And Acme issues the credential
  Then Bob receives the issued credential

@TEST_ATL-3894
Scenario: Issuing credential with unpublished PRISM DID to unpublished PRISM DID
  Given Acme and Bob have an existing connection
  When Acme creates unpublished DID
  And Bob creates unpublished DID
  And Acme offers a credential to Bob with "long" form DID
  And Bob receives the credential offer and accepts
  And Acme issues the credential
  Then Bob receives the issued credential

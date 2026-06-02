Put the SHARED claim credentials here (same for every bin):
  claim.cert.pem        (from aws/claim_certs/ after running the fleet deploy)
  claim.private.key
These let a blank device provision itself ONCE; it then gets its own unique
cert over the air and stores it in NVS. Keep the claim key secret.

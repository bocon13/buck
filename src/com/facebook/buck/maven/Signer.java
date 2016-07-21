package com.facebook.buck.maven;

import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.BCPGOutputStream;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPSecretKeyRing;
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureGenerator;
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder;
import org.eclipse.aether.deployment.DeploymentException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.Security;
import java.util.Iterator;

/**
 * Helper class that will generate ASCII-armored PGP signatures for any file.
 * This is used to sign files that will be deployed to Maven Central.
 */
public class Signer {

  private static final int READ_BUFFER_SIZE = 8096;
  static {
    Security.addProvider(new BouncyCastleProvider());
  }

  //FIXME perhaps introduce a builder pattern here to construct these objects
  private final String keyring;
  private final String password;
  private final File input;

  private File output;
  private boolean signed;

  public Signer(String keyring, String password, File input) {
    this.keyring = keyring;
    this.password = password;
    this.input = input;
  }

  public File getSignatureFile() throws DeploymentException {

    if (!signed) {
      try {
        output = File.createTempFile(input.getName(), ".asc");
      } catch (IOException e) {
        throw new DeploymentException("Could not create temp file for signature", e);
      }

      sign(output);
      signed = true;
    }
    return output;
  }

  /*
   * This method is adapted from:
   *   org.bouncycastle.openpgp.examples.PGPExampleUtil.readSecretKey(java.io.InputStream)
   */
  private PGPSecretKey getSecretKey(File keyring) throws DeploymentException {

    final InputStream keyStream;
    try {
      keyStream = new FileInputStream(keyring);
    } catch (FileNotFoundException e) {
      throw new DeploymentException(
          String.format("Could not find GPG keyring: %s", keyring), e);
    }

    final PGPSecretKeyRingCollection pgpSecretKeyRingCollection;
    try {
      pgpSecretKeyRingCollection = new PGPSecretKeyRingCollection(
          PGPUtil.getDecoderStream(keyStream), new JcaKeyFingerprintCalculator());
    } catch (IOException | PGPException e) {
      throw new DeploymentException("Error reading GPG keyring", e);
    }

    // FIXME
    // we just loop through the collection till we find a key suitable for encryption, in the real
    // world you would probably want to be a bit smarter about this.
    //
    Iterator<PGPSecretKeyRing> keyRingIter = pgpSecretKeyRingCollection.getKeyRings();
    while (keyRingIter.hasNext())
    {
      PGPSecretKeyRing keyRing = keyRingIter.next();

      Iterator<PGPSecretKey> keyIter = keyRing.getSecretKeys();
      while (keyIter.hasNext())
      {
        PGPSecretKey key = keyIter.next();

        if (key.isSigningKey())
        {
          return key;
        }
      }
    }

    throw new DeploymentException("Could not find suitable GPG key");
  }

  private PGPPrivateKey getPrivateKey(PGPSecretKey pgpSec, String password) throws DeploymentException {
    try {
      return pgpSec.extractPrivateKey(new JcePBESecretKeyDecryptorBuilder()
                   .setProvider("BC")
                   .build(password.toCharArray()));
    } catch (PGPException e) {
      throw new DeploymentException("Could not extract private key", e);
    }
  }

  private PGPSignatureGenerator getSignatureGenerator(PGPSecretKey secretKey,
                                                      PGPPrivateKey privateKey)
      throws DeploymentException {
    PGPSignatureGenerator sGen = new PGPSignatureGenerator(
        new JcaPGPContentSignerBuilder(
            secretKey.getPublicKey().getAlgorithm(), PGPUtil.SHA1)
            .setProvider("BC"));

    try {
      sGen.init(PGPSignature.BINARY_DOCUMENT, privateKey);
    } catch (PGPException e) {
      throw new DeploymentException("Could not initialize PGP signature generator", e);
    }

    @SuppressWarnings("rawtypes")
    Iterator it = secretKey.getPublicKey().getUserIDs();
    if (it.hasNext()) {
      PGPSignatureSubpacketGenerator spGen = new PGPSignatureSubpacketGenerator();

      spGen.setSignerUserID(false, (String) it.next());
      sGen.setHashedSubpackets(spGen.generate());
    }

    return sGen;
  }

  private void sign(File output) throws DeploymentException {

    PGPSecretKey secretKey = getSecretKey(new File(keyring));
    PGPPrivateKey privateKey = getPrivateKey(secretKey, password);
    PGPSignatureGenerator sGen = getSignatureGenerator(secretKey, privateKey);

    try (OutputStream rawOut = new FileOutputStream(output);
         FileInputStream in = new FileInputStream(input);
         OutputStream out = new ArmoredOutputStream(rawOut);
         BCPGOutputStream bOut = new BCPGOutputStream(out)) {
      int ch;
      byte[] buf = new byte[READ_BUFFER_SIZE];
      while ((ch = in.read(buf)) >= 0) {
        sGen.update(buf, 0, ch);
      }
      sGen.generate().encode(bOut);
    } catch (IOException | PGPException e) {
      throw new DeploymentException(
          String.format("Error generating PGP signature for file: %s", input.getName()), e);
    }
  }

}

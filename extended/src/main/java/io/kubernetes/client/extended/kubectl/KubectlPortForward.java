/*
Copyright 2020 The Kubernetes Authors.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at
http://www.apache.org/licenses/LICENSE-2.0
Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package io.kubernetes.client.extended.kubectl;

import static io.kubernetes.client.extended.kubectl.KubectlExec.copyAsync;

import io.kubernetes.client.PortForward;
import io.kubernetes.client.extended.kubectl.exception.KubectlException;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.V1Pod;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public class KubectlPortForward
    extends Kubectl.ResourceAndContainerBuilder<V1Pod, KubectlPortForward>
    implements Kubectl.Executable<Boolean> {
  List<Integer> localPorts;
  List<Integer> targetPorts;
  boolean running;

  KubectlPortForward(ApiClient client) {
    super(client, V1Pod.class);

    localPorts = new ArrayList<>();
    targetPorts = new ArrayList<>();
  }

  /**
   * Add a port pair
   *
   * @param localPort The port to listen to on the local machine
   * @param targetPort The port to connect to on the target Pod
   */
  public KubectlPortForward ports(int localPort, int targetPort) {
    localPorts.add(localPort);
    targetPorts.add(targetPort);
    return this;
  }

  @Override
  public Boolean execute() throws KubectlException {
    running = true;
    try {
      executeInternal();
      return true;
    } catch (ApiException | IOException | InterruptedException ex) {
      throw new KubectlException(ex);
    }
  }

  public void shutdown() {
    running = false;
  }

  private void executeInternal()
      throws ApiException, KubectlException, IOException, InterruptedException {
    PortForward pf = new PortForward(apiClient);
    PortForward.PortForwardResult result = pf.forward(namespace, name, targetPorts);
    if (result == null) {
      throw new KubectlException("PortForward failed!");
    }
    // TODO: Convert this to NIO to reduce the number of threads?
    List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < localPorts.size(); i++) {
      int targetPort = targetPorts.get(i);
      threads.add(
          portForward(
              new ServerSocket(localPorts.get(i)),
              result.getInputStream(targetPort),
              result.getOutboundStream(targetPort)));
    }
    for (Thread t : threads) {
      t.join();
    }
  }

  private Thread portForward(ServerSocket server, InputStream in, OutputStream out) {
    Thread t =
        new Thread(
            new Runnable() {
              @Override
              public void run() {
                while (running) {
                  try {
                    Socket sock = server.accept();
                    Thread t1 = copyAsync(sock.getInputStream(), out);
                    Thread t2 = copyAsync(in, sock.getOutputStream());

                    t1.join();
                    t2.join();
                  } catch (InterruptedException | IOException ex) {
                    ex.printStackTrace();
                  }
                }
              }
            });
    t.start();
    return t;
  }
}

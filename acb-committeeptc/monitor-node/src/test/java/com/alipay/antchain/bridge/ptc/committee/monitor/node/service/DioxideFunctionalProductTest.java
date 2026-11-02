package com.alipay.antchain.bridge.ptc.committee.monitor.node.service;
import com.alipay.antchain.bridge.ptc.committee.monitor.node.commons.models.BtaWrapper;
import com.alipay.antchain.bridge.ptc.committee.monitor.node.service.impl.EndorserServiceImpl;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
public class DioxideFunctionalProductTest {
 @Test public void compatibilityBranchIsRestrictedToTheTwoDioxideProducts() throws Exception {
  var service=new EndorserServiceImpl();
  var method=EndorserServiceImpl.class.getDeclaredMethod("isDioxideFunctionalTestProduct",BtaWrapper.class);
  method.setAccessible(true);
  var bta=mock(BtaWrapper.class);
  for(String product:new String[]{"dioxide","dioxide2","DIOXIDE","ethereum3","mychain2","fiscobcos3","dioxide-lookalike"}) {
   when(bta.getProduct()).thenReturn(product);
   assertEquals(product.equalsIgnoreCase("dioxide")||product.equalsIgnoreCase("dioxide2"),method.invoke(service,bta));
  }
 }
}

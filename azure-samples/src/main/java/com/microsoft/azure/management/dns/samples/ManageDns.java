package com.microsoft.azure.management.dns.samples;

import com.microsoft.azure.management.Azure;
import com.microsoft.azure.management.appservice.AppServicePricingTier;
import com.microsoft.azure.management.appservice.CustomHostNameDnsRecordType;
import com.microsoft.azure.management.appservice.WebApp;
import com.microsoft.azure.management.compute.KnownWindowsVirtualMachineImage;
import com.microsoft.azure.management.compute.VirtualMachine;
import com.microsoft.azure.management.compute.VirtualMachineSizeTypes;
import com.microsoft.azure.management.dns.DnsRecordSet;
import com.microsoft.azure.management.dns.DnsZone;
import com.microsoft.azure.management.network.PublicIpAddress;
import com.microsoft.azure.management.resources.ResourceGroup;
import com.microsoft.azure.management.resources.fluentcore.arm.Region;
import com.microsoft.azure.management.resources.fluentcore.utils.ResourceNamer;
import com.microsoft.azure.management.samples.Utils;
import okhttp3.logging.HttpLoggingInterceptor;

import java.io.File;

/**
 * Azure DNS sample for managing DNS zones.
 *  - Create a root DNS zone (contoso.com)
 *  - Create a web application
 *  - Add a CNAME record (www) to root DNS zone and bind it to web application host name
 *  - Creates a virtual machine with public IP
 *  - Add a A record (employees) to root DNS zone that points to virtual machine public IPV4 address
 *  - Creates a child DNS zone (partners.contoso.com)
 *  - Creates a virtual machine with public IP
 *  - Add a A record (partners) to child DNS zone that points to virtual machine public IPV4 address
 *  - Delegate from parent domain to child domain by adding NS records
 *  - Remove A record from the parent DNS zone
 *  - Delete the child DNS zone
 */
public class ManageDns {
    /**
     * Main entry point.
     * @param args the parameters
     */
    public static void main(String[] args) {
        final String customDomainName         = "THE CUSTOM DOMAIN THAT YOU OWN (e.g. contoso.com)";
        final String rgName                   = ResourceNamer.randomResourceName("rgNEMV_", 24);
        final String appServicePlanName       = ResourceNamer.randomResourceName("jplan1_", 15);
        final String webAppName               = ResourceNamer.randomResourceName("webapp1-", 20);
        Azure azure;

        try {
            //=============================================================
            // Authenticate

            final File credFile = new File(System.getenv("AZURE_AUTH_LOCATION"));

            azure = Azure
                    .configure()
                    .withLogLevel(HttpLoggingInterceptor.Level.BASIC)
                    .authenticate(credFile)
                    .withDefaultSubscription();

            // Print selected subscription
            System.out.println("Selected subscription: " + azure.subscriptionId());

            try {
                ResourceGroup resourceGroup = azure.resourceGroups().define(rgName)
                        .withRegion(Region.US_EAST2)
                        .create();

                //============================================================
                // Creates root DNS Zone

                System.out.println("Creating root DNS zone " + customDomainName + "...");
                DnsZone rootDnsZone = azure.dnsZones().define(customDomainName)
                    .withExistingResourceGroup(resourceGroup)
                    .create();

                System.out.println("Created root DNS zone " + rootDnsZone.name());
                Utils.print(rootDnsZone);

                //============================================================
                // Sets NS records in the parent zone (hosting custom domain) to make Azure DNS the authoritative
                // source for name resolution for the zone

                System.out.println("Go to your registrar portal and configure your domain " + customDomainName + " with following name server addresses");
                for (String nameServer : rootDnsZone.nameServers()) {
                    System.out.println(" " + nameServer);
                }
                System.out.println("Press a key after finishing above step");
                System.in.read();

                //============================================================
                // Creates a web App

                System.out.println("Creating Web App " + webAppName + "...");
                WebApp webApp = azure.webApps().define(webAppName)
                        .withExistingResourceGroup(rgName)
                        .withNewAppServicePlan(appServicePlanName)
                        .withRegion(Region.US_EAST2)
                        .withPricingTier(AppServicePricingTier.BASIC_B1)
                        .defineSourceControl()
                            .withPublicGitRepository("https://github.com/jianghaolu/azure-site-test")
                            .withBranch("master")
                            .attach()
                        .create();
                System.out.println("Created web app " + webAppName);
                Utils.print(webApp);

                //============================================================
                // Adds CName Dns record to root DNS zone that specify web app host domain as an alias for www.[customDomainName]

                System.out.println("Updating DNS zone by adding a CName record...");
                rootDnsZone = rootDnsZone.update()
                        .withCnameRecordSet("www", webApp.defaultHostName())
                        .apply();
                System.out.println("DNS zone updated");
                Utils.print(rootDnsZone);

                //============================================================
                // Adds a web app host name binding for www.[customDomainName]

                System.out.println("Updating Web app with host name binding...");
                webApp.update()
                        .defineHostnameBinding()
                            .withThirdPartyDomain(customDomainName)
                            .withSubDomain("www")
                            .withDnsRecordType(CustomHostNameDnsRecordType.CNAME)
                            .attach()
                        .apply();
                System.out.println("Web app updated");
                Utils.print(webApp);

                //============================================================
                // Creates a virtual machine with public IP

                System.out.println("Creating a virtual machine with public IP...");
                VirtualMachine virtualMachine1 = azure.virtualMachines()
                        .define(ResourceNamer.randomResourceName("employeesvm-", 20))
                        .withRegion(Region.US_EAST)
                        .withExistingResourceGroup(resourceGroup)
                        .withNewPrimaryNetwork("10.0.0.0/28")
                        .withPrimaryPrivateIpAddressDynamic()
                        .withNewPrimaryPublicIpAddress(ResourceNamer.randomResourceName("employeespip-", 15))
                        .withPopularWindowsImage(KnownWindowsVirtualMachineImage.WINDOWS_SERVER_2012_R2_DATACENTER)
                        .withAdminUsername("testuser")
                        .withAdminPassword("12NewPA$$w0rd!")
                        .withSize(VirtualMachineSizeTypes.STANDARD_D1_V2)
                        .create();
                System.out.println("Virtual machine created");

                //============================================================
                // Update DNS zone by adding a A record in root DNS zone pointing to virtual machine IPv4 address

                PublicIpAddress vm1PublicIpAddress = virtualMachine1.getPrimaryPublicIpAddress();
                System.out.println("Updating root DNS zone " + customDomainName + "...");
                rootDnsZone = rootDnsZone.update()
                        .defineARecordSet("employees")
                            .withIpv4Address(vm1PublicIpAddress.ipAddress())
                        .   attach()
                        .apply();
                System.out.println("Updated root DNS zone " + rootDnsZone.name());
                Utils.print(rootDnsZone);

                //============================================================
                // Creates a child DNS zone

                String partnerSubDomainName  = "partners." + customDomainName;
                System.out.println("Creating child DNS zone " + partnerSubDomainName + "...");
                DnsZone partnersDnsZone = azure.dnsZones()
                        .define(partnerSubDomainName)
                        .withExistingResourceGroup(resourceGroup)
                        .create();
                System.out.println("Created child DNS zone " + partnersDnsZone.name());
                Utils.print(partnersDnsZone);

                //============================================================
                // Adds NS records in the root dns zone to delegate partners.[customDomainName] to child dns zone

                System.out.println("Updating parent DNS zone " + rootDnsZone + "...");
                DnsRecordSet.UpdateDefinitionStages.NsRecordSetBlank<DnsZone.Update> nsRecordsetStage = rootDnsZone.update()
                        .defineNsRecordSet("partners");
                DnsRecordSet
                        .UpdateDefinitionStages
                        .WithNsRecordNameServerOrAttachable<DnsZone.Update> nsRecordStage = null;
                for (String nameServerName : partnersDnsZone.nameServers()) {
                    nsRecordsetStage.withNameServer(nameServerName);
                }
                nsRecordStage
                        .attach()
                        .apply();
                System.out.println("Root DNS zone updated");
                Utils.print(rootDnsZone);

                //============================================================
                // Creates a virtual machine with public IP

                System.out.println("Creating a virtual machine with public IP...");
                VirtualMachine virtualMachine2 = azure.virtualMachines()
                        .define(ResourceNamer.randomResourceName("partnersvm-", 20))
                        .withRegion(Region.US_EAST)
                        .withExistingResourceGroup(resourceGroup)
                        .withNewPrimaryNetwork("10.0.0.0/28")
                        .withPrimaryPrivateIpAddressDynamic()
                        .withNewPrimaryPublicIpAddress(ResourceNamer.randomResourceName("partnerspip-", 15))
                        .withPopularWindowsImage(KnownWindowsVirtualMachineImage.WINDOWS_SERVER_2012_R2_DATACENTER)
                        .withAdminUsername("testuser")
                        .withAdminPassword("12NewPA$$w0rd!")
                        .withSize(VirtualMachineSizeTypes.STANDARD_D1_V2)
                        .create();
                System.out.println("Virtual machine created");

                //============================================================
                // Update child Dns zone by adding a A record pointing to virtual machine IPv4 address

                PublicIpAddress vm2PublicIpAddress = virtualMachine2.getPrimaryPublicIpAddress();
                System.out.println("Updating child DNS zone " + partnerSubDomainName + "...");
                partnersDnsZone = partnersDnsZone.update()
                        .defineARecordSet("@")
                            .withIpv4Address(vm2PublicIpAddress.ipAddress())
                            .attach()
                        .apply();
                System.out.println("Updated child DNS zone " + partnersDnsZone.name());
                Utils.print(partnersDnsZone);

                //============================================================
                // Removes A record entry from the root DNS zone

                System.out.println("Removing A Record from root DNS zone " + rootDnsZone.name() + "...");
                rootDnsZone = rootDnsZone.update()
                        .withoutARecordSet("employees")
                        .apply();
                System.out.println("Removed A Record from root DNS zone");
                Utils.print(rootDnsZone);

                //============================================================
                // Deletes the Dns zone

                System.out.println("Deleting child DNS zone " + partnersDnsZone.name() + "...");
                azure.dnsZones().deleteById(partnersDnsZone.id());
                System.out.println("Deleted child DNS zone " + partnersDnsZone.name());

            } catch (Exception e) {
                System.err.println(e.getMessage());
                e.printStackTrace();
            } finally {
                try {
                    System.out.println("Deleting Resource Group: " + rgName);
                    azure.resourceGroups().beginDeleteByName(rgName);
                    System.out.println("Deleted Resource Group: " + rgName);
                } catch (NullPointerException npe) {
                    System.out.println("Did not create any resources in Azure. No clean up is necessary");
                } catch (Exception g) {
                    g.printStackTrace();
                }
            }
        } catch (Exception e) {
            System.out.println(e.getMessage());
            e.printStackTrace();
        }
    }
}

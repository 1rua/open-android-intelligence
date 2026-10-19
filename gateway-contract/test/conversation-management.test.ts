import { describe, expect, it } from "vitest";
import { validateGatewayValue } from "../src/schema-registry.js";

const organization={revision:3,sortMode:"custom",customOrder:["conv_one","conv_two"],
  folders:[{folderId:"folder_work",name:"Work"}],assignments:{conv_one:"folder_work"}};

describe("shared conversation management contract",()=> {
  it("uses expectedRevision on writes and revision on server state",()=> {
    expect(validateGatewayValue("conversation.organization",organization).ok).toBe(true);
    const {revision,...fields}=organization;
    expect(validateGatewayValue("conversation.organizationReplace",{...fields,expectedRevision:revision}).ok).toBe(true);
    expect(validateGatewayValue("conversation.organizationReplace",organization).ok).toBe(false);
    expect(validateGatewayValue("conversation.organization",{...fields,expectedRevision:revision}).ok).toBe(false);
    expect(validateGatewayValue("conversation.organizationReplace",{...fields,expectedRevision:-1}).ok).toBe(false);
  });
  it("rejects unknown sort modes, duplicate order ids and invalid folder names",()=> {
    expect(validateGatewayValue("conversation.organization",{...organization,sortMode:"recent"}).ok).toBe(false);
    expect(validateGatewayValue("conversation.organization",{...organization,customOrder:["conv_one","conv_one"]}).ok).toBe(false);
    for(const name of [""," "," leading","trailing ","two\nlines"]) {
      expect(validateGatewayValue("conversation.organization",{...organization,folders:[{folderId:"folder_work",name}]}).ok).toBe(false);
    }
  });
  it("requires authoritative archive flags, update timestamps and pagination metadata",()=> {
    const page={conversations:[{conversationId:"conv_one",clientConversationId:"cc_one",title:null,archived:true,updatedAt:"2026-10-18T12:00:00.000Z"}],nextCursor:null,snapshotRevision:7};
    expect(validateGatewayValue("conversation.listPage",page).ok).toBe(true);
    expect(validateGatewayValue("conversation.listPage",{...page,snapshotRevision:0}).ok).toBe(false);
    expect(validateGatewayValue("conversation.listPage",{...page,conversations:[{...page.conversations[0],updatedAt:"yesterday"}]}).ok).toBe(false);
    expect(validateGatewayValue("conversation.archive",{archived:true}).ok).toBe(true);
    expect(validateGatewayValue("conversation.archive",{archived:"true"}).ok).toBe(false);
    expect(validateGatewayValue("conversation.archive",{archived:true,title:"mixed update"}).ok).toBe(false);
  });
});

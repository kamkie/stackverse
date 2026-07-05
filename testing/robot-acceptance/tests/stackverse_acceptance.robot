*** Settings ***
Documentation       Keyword-driven Robot Framework acceptance showcase for Stackverse.
Resource            ../resources/stackverse_keywords.resource
Suite Setup         Prepare Robot Acceptance Suite
Test Setup          Open Stackverse Browser
Test Teardown       Close Stackverse Browser
Test Tags           robot-acceptance    showcase


*** Test Cases ***
Login Session Uses The Gateway And Keycloak
    [Documentation]    Demonstrates the real OIDC browser flow and gateway-owned session cookie.
    Anonymous Visitor Sees Login
    Login As Stackverse User    demo
    Signed In User Should Be    demo
    Log Out Of Stackverse

Bookmark Creation Editing And Deletion Use Domain Keywords
    [Documentation]    Exercises the bookmark dialog through reusable domain-level keywords.
    ${marker}=    Unique Marker
    Login As Stackverse User    demo
    Go To Stackverse Path    /bookmarks
    Create Bookmark
    ...    https://example.com/robot/${marker}
    ...    robot create ${marker}
    ...    robot notes ${marker}
    ...    robot-${marker}
    ...    private
    Edit Bookmark Title    robot create ${marker}    robot edited ${marker}
    Delete Bookmark    robot edited ${marker}

Authenticated Users Can Report Public Bookmarks
    [Documentation]    Shows the public feed report workflow as readable acceptance steps.
    ${marker}=    Unique Marker
    Login As Stackverse User    demo
    Go To Stackverse Path    /bookmarks
    Create Bookmark
    ...    https://example.com/robot/report/${marker}
    ...    robot reportable ${marker}
    ...    ${EMPTY}
    ...    ${EMPTY}
    ...    public
    Go To Stackverse Path    /feed
    Report Public Bookmark    robot reportable ${marker}    robot report ${marker}

Moderators Can Action Reports And Hide Public Bookmarks
    [Documentation]    Covers a representative multi-role moderation path without expanding the contract.
    ${marker}=    Unique Marker
    Login As Stackverse User    demo
    Go To Stackverse Path    /bookmarks
    Create Bookmark
    ...    https://example.com/robot/moderation/${marker}
    ...    robot moderated ${marker}
    ...    ${EMPTY}
    ...    ${EMPTY}
    ...    public
    Go To Stackverse Path    /feed
    Report Public Bookmark    robot moderated ${marker}    robot moderation report ${marker}
    Log Out Of Stackverse
    Login As Stackverse User    moderator
    Go To Stackverse Path    /admin/reports
    Action Open Report    robot moderation report ${marker}
    Public Feed Should Not Show Bookmark    robot moderated ${marker}

Admins See Full Backoffice Navigation
    [Documentation]    Keeps the admin check intentionally small: role-gated backoffice access and links.
    Login As Stackverse User    admin
    Admin Backoffice Should Show Full Navigation

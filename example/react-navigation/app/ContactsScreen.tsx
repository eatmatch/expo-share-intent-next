import React, { useEffect, useState } from "react";
import {
  View,
  Text,
  StyleSheet,
  FlatList,
  Image,
  TouchableOpacity,
  Alert,
  ActivityIndicator,
  Platform,
} from "react-native";
import { useShareIntentContext } from "expo-share-intent-next";
import { NativeStackNavigationProp } from "@react-navigation/native-stack";
import { RootStackParamList } from "./types";

interface Contact {
  id: string;
  name: string;
  avatar: string | null;
  lastMessage: string;
}

// Generate a large number of mock contacts
const generateMockContacts = (count: number): Contact[] => {
  const avatarUrls = [
    "https://i.pravatar.cc/150?img=1",
    "https://i.pravatar.cc/150?img=2",
    "https://i.pravatar.cc/150?img=3",
    "https://i.pravatar.cc/150?img=4",
    "https://i.pravatar.cc/150?img=5",
    "https://i.pravatar.cc/150?img=6",
    "https://i.pravatar.cc/150?img=7",
    "https://i.pravatar.cc/150?img=8",
    null,
  ];

  const firstNames = [
    "John",
    "Jane",
    "Mike",
    "Sarah",
    "David",
    "Emma",
    "Alex",
    "Olivia",
    "James",
    "Lisa",
    "Robert",
    "Linda",
    "William",
    "Elizabeth",
    "Richard",
    "Mary",
    "Thomas",
    "Susan",
    "Michael",
    "Jennifer",
  ];

  const lastNames = [
    "Smith",
    "Johnson",
    "Williams",
    "Jones",
    "Brown",
    "Davis",
    "Miller",
    "Wilson",
    "Moore",
    "Taylor",
    "Anderson",
    "Thomas",
    "Jackson",
    "White",
    "Harris",
    "Martin",
    "Thompson",
    "Garcia",
    "Martinez",
    "Robinson",
  ];

  const messages = [
    "Hey, how are you?",
    "Did you see the news?",
    "Let's meet tomorrow",
    "I just sent you a file",
    "Can you help me with something?",
    "Thanks for the information",
    "I'll call you later",
    "Are you free this weekend?",
    "Check out this link",
    "This is amazing!",
  ];

  return Array.from({ length: count }, (_, index) => {
    const firstName = firstNames[Math.floor(Math.random() * firstNames.length)];
    const lastName = lastNames[Math.floor(Math.random() * lastNames.length)];

    return {
      id: `contact-${index}`,
      name: `${firstName} ${lastName}`,
      avatar: avatarUrls[Math.floor(Math.random() * avatarUrls.length)],
      lastMessage: messages[Math.floor(Math.random() * messages.length)],
    };
  });
};

interface Props {
  navigation: NativeStackNavigationProp<RootStackParamList, "Contacts">;
}

export default function ContactsScreen({ navigation }: Props) {
  const [contacts, setContacts] = useState<Contact[]>([]);
  const [donatedContacts, setDonatedContacts] = useState<
    Record<string, boolean | "loading">
  >({});
  const [loading, setLoading] = useState(true);
  const { donateSendMessage, publishDirectShareTargets } =
    useShareIntentContext();

  useEffect(() => {
    // Generate 100 mock contacts
    const mockContacts = generateMockContacts(100);
    setContacts(mockContacts);

    if (Platform.OS === "android") {
      setLoading(true);
      console.log("Publishing Direct Share targets...");

      // Convert contacts to the format expected by publishDirectShareTargets
      const contactsForSharing = mockContacts.slice(0, 15).map((contact) => ({
        id: contact.id,
        name: contact.name,
        imageURL: contact.avatar || undefined,
      }));

      // Publish all contacts at once using the new method
      publishDirectShareTargets(contactsForSharing)
        .then((success) => {
          console.log("Published Direct Share targets:", success);

          // Mark all contacts as donated
          const donated: Record<string, boolean> = {};
          contactsForSharing.forEach((contact) => {
            donated[contact.id] = true;
          });

          setDonatedContacts(donated);
          setLoading(false);
        })
        .catch((error) => {
          console.error("Failed to publish Direct Share targets:", error);
          setLoading(false);
        });
    } else {
      // For iOS or other platforms, use the original donateSendMessage approach
      setLoading(true);
      console.log("Donating 15 Direct Share targets...");

      // Track donated status
      const donated: Record<string, boolean> = {};

      // Use Promise.all to track when all contacts are donated
      const donatePromises = mockContacts.slice(0, 15).map((contact) => {
        return new Promise<void>((resolve) => {
          donateSendMessage({
            conversationId: contact.id,
            name: contact.name,
            image: {
              uri: contact.avatar || undefined,
            },
            content: contact.lastMessage,
          })
            .then(() => {
              donated[contact.id] = true;
              setDonatedContacts((prev) => ({ ...prev, [contact.id]: true }));
              resolve();
            })
            .catch(() => {
              // Mark as false if there was an error
              donated[contact.id] = false;
              setDonatedContacts((prev) => ({ ...prev, [contact.id]: false }));
              resolve();
            });
        });
      });

      // When all donations are complete
      Promise.all(donatePromises).then(() => {
        setLoading(false);
        console.log("Finished donating Direct Share targets");
      });
    }
  }, [donateSendMessage, publishDirectShareTargets]);

  const handleContactPress = (contact: Contact) => {
    // Show loading state for this contact
    setDonatedContacts((prev) => ({ ...prev, [contact.id]: "loading" }));

    // Simulate sending a message by donating the contact again
    donateSendMessage({
      conversationId: contact.id,
      name: contact.name,
      image: {
        uri: contact.avatar || undefined,
      },
      content: `New message to ${contact.name} (${new Date().toLocaleTimeString()})`,
    });

    // Update status to success
    setDonatedContacts((prev) => ({ ...prev, [contact.id]: true }));

    Alert.alert(
      "Contact Shortcut Donated",
      `${contact.name} has been donated as a Direct Share target. You can now share content directly to this contact from other apps.`,
      [{ text: "OK" }],
    );
  };

  return (
    <View style={styles.container}>
      <Text style={styles.header}>Contacts ({contacts.length})</Text>
      {loading ? (
        <View style={styles.loadingContainer}>
          <ActivityIndicator size="large" color="#3498db" />
          <Text style={styles.loadingText}>
            Donating 15 contacts as Direct Share targets...
          </Text>
        </View>
      ) : (
        <Text style={styles.subheader}>
          Top 15 contacts have been donated as Direct Share targets
        </Text>
      )}

      <FlatList
        data={contacts}
        keyExtractor={(item) => item.id}
        renderItem={({ item, index }) => (
          <TouchableOpacity
            style={styles.contactItem}
            onPress={() => handleContactPress(item)}
            disabled={donatedContacts[item.id] === "loading"}
          >
            {item.avatar ? (
              <Image source={{ uri: item.avatar }} style={styles.avatar} />
            ) : (
              <View style={styles.avatarPlaceholder}>
                <Text style={styles.avatarLetter}>{item.name[0]}</Text>
              </View>
            )}
            <View style={styles.contactDetails}>
              <Text style={styles.name}>{item.name}</Text>
              <Text style={styles.message}>{item.lastMessage}</Text>

              {index < 15 && (
                <View style={styles.donationStatus}>
                  {donatedContacts[item.id] === undefined ? (
                    <Text style={styles.donationPending}>
                      Pending donation...
                    </Text>
                  ) : donatedContacts[item.id] === "loading" ? (
                    <View style={styles.donationLoading}>
                      <ActivityIndicator size="small" color="#3498db" />
                      <Text style={styles.donationLoadingText}>
                        Donating...
                      </Text>
                    </View>
                  ) : donatedContacts[item.id] ? (
                    <Text style={styles.donationSuccess}>
                      ✓ Donated as Direct Share target
                    </Text>
                  ) : (
                    <Text style={styles.donationFailure}>
                      ⨯ Donation failed
                    </Text>
                  )}
                </View>
              )}
            </View>
          </TouchableOpacity>
        )}
      />

      <TouchableOpacity
        style={styles.backButton}
        onPress={() => navigation.navigate("Home")}
      >
        <Text style={styles.backButtonText}>Go to Home</Text>
      </TouchableOpacity>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    padding: 16,
    backgroundColor: "#fff",
  },
  header: {
    fontSize: 24,
    fontWeight: "bold",
    marginBottom: 8,
  },
  subheader: {
    fontSize: 14,
    color: "#666",
    marginBottom: 16,
  },
  contactItem: {
    flexDirection: "row",
    padding: 12,
    borderBottomWidth: 1,
    borderBottomColor: "#eee",
    alignItems: "center",
  },
  avatar: {
    width: 50,
    height: 50,
    borderRadius: 25,
  },
  avatarPlaceholder: {
    width: 50,
    height: 50,
    borderRadius: 25,
    backgroundColor: "#3498db",
    justifyContent: "center",
    alignItems: "center",
  },
  avatarLetter: {
    color: "white",
    fontSize: 20,
    fontWeight: "bold",
  },
  contactDetails: {
    marginLeft: 12,
    flex: 1,
  },
  name: {
    fontSize: 18,
    fontWeight: "500",
  },
  message: {
    fontSize: 14,
    color: "#666",
    marginTop: 4,
  },
  backButton: {
    backgroundColor: "#3498db",
    padding: 16,
    borderRadius: 8,
    marginTop: 16,
    alignItems: "center",
  },
  backButtonText: {
    color: "white",
    fontWeight: "bold",
    fontSize: 16,
  },
  loadingContainer: {
    flex: 1,
    justifyContent: "center",
    alignItems: "center",
    padding: 20,
  },
  loadingText: {
    fontSize: 16,
    color: "#666",
    marginTop: 12,
    textAlign: "center",
  },
  donationStatus: {
    marginTop: 8,
  },
  donationPending: {
    fontSize: 12,
    color: "#f39c12",
    fontStyle: "italic",
  },
  donationLoading: {
    flexDirection: "row",
    alignItems: "center",
  },
  donationLoadingText: {
    fontSize: 12,
    color: "#3498db",
    marginLeft: 4,
  },
  donationSuccess: {
    fontSize: 12,
    color: "#27ae60",
    fontWeight: "500",
  },
  donationFailure: {
    fontSize: 12,
    color: "#e74c3c",
    fontWeight: "500",
  },
});
